package com.teachlearn.im.reactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第 3 章 demo：主从 Reactor。
 *
 * <h2>本类的核心目的</h2>
 * 演示 <b>主从 Reactor</b> 模型——把"接收连接"和"处理已连接 IO"分配到不同线程组，
 * 解决 {@link SingleReactorServer} 的两个缺陷：
 * <ul>
 *     <li>主 Reactor 只处理 accept，不做业务 → 不再因业务阻塞而卡 accept</li>
 *     <li>从 Reactor 有 N 个线程 → 充分利用多核 CPU</li>
 * </ul>
 *
 * <h2>这其实就是 Netty 的 EventLoopGroup 模型（关键关联）</h2>
 * <ul>
 *     <li>mainReactor ≈ Netty 的 {@code bossGroup}（默认 1 个线程）</li>
 *     <li>subReactorGroup ≈ Netty 的 {@code workerGroup}（默认 2 × CPU 核数）</li>
 *     <li>round-robin 分配连接 ≈ Netty 默认的 PowerOfTwoEventExecutorChooser</li>
 * </ul>
 *
 * <h2>本章的重点难点：跨线程注册</h2>
 * 一个客户端 channel 在 mainReactor 线程上被 accept，
 * 但要被注册到 subReactor 的 selector 上。
 * Java NIO 规定：{@code selector.select()} 阻塞期间，其他线程
 * 调 {@code channel.register()} 会阻塞到 select() 唤醒；
 * 解法是先 {@code subSel.wakeup()} 再 register。
 */
public final class MasterSlaveReactorServer {

    /** 默认端口。9095=主从 Reactor，与 9094 单 Reactor 区分开。 */
    public static final int DEFAULT_PORT = 9095;

    /**
     * 子 Reactor 数量。
     * <p>经验值：CPU 核数 / 2（因为还要算上主 Reactor、GC 线程、其它进程）；
     * 本 demo 直接用 {@code availableProcessors() / 2}，最少 1 个。
     */
    public static final int SUB_REACTOR_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    /** 工具类不允许实例化。 */
    private MasterSlaveReactorServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /**
     * 启动流程（建议背诵）：
     * <ol>
     *     <li>先开 N 个 subReactor 线程，每个跑一个 selector 循环</li>
     *     <li>主线程起一个 mainReactor 循环，只处理 accept</li>
     *     <li>accept 后 round-robin 分配一个 subReactor，wakeup 后注册</li>
     * </ol>
     */
    private static void start(int port) throws IOException {
        Selector[] subSelectors = new Selector[SUB_REACTOR_COUNT];
        Thread[] subThreads = new Thread[SUB_REACTOR_COUNT];
        // round-robin 计数器，无锁原子自增
        AtomicInteger nextSub = new AtomicInteger(0);

        // 步骤 1：先启动 N 个 subReactor 线程
        for (int i = 0; i < SUB_REACTOR_COUNT; i++) {
            subSelectors[i] = Selector.open();
            int idx = i;   // lambda 捕获的局部变量需要 effectively final
            subThreads[i] = new Thread(() -> runSubLoop(subSelectors[idx]),
                    "sub-reactor-" + i);
            subThreads[i].setDaemon(true);   // 守护线程，main 退出时自动 kill
            subThreads[i].start();
        }

        // 步骤 2：mainReactor 跑在主线程
        try (Selector mainSelector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open()) {

            server.bind(new InetSocketAddress(port));
            server.configureBlocking(false);
            // mainReactor 只关心 accept 事件
            server.register(mainSelector, SelectionKey.OP_ACCEPT);

            System.out.println("[master-slave] listening on port " + port);
            System.out.println("[master-slave] sub reactor count = " + SUB_REACTOR_COUNT);

            // mainReactor 主循环
            while (mainSelector.select() > 0 || !Thread.interrupted()) {
                Set<SelectionKey> ready = mainSelector.selectedKeys();
                Iterator<SelectionKey> it = ready.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    // mainReactor 只关心 accept；其它事件（理论不该出现）忽略
                    if (!key.isAcceptable()) {
                        continue;
                    }
                    ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                    SocketChannel client = ssc.accept();
                    client.configureBlocking(false);

                    // round-robin 选一个 subReactor
                    // Math.floorMod 处理负数（虽然这里自增是单调正数，但 floorMod 更安全）
                    int subIdx = Math.floorMod(nextSub.getAndIncrement(), SUB_REACTOR_COUNT);
                    Selector subSel = subSelectors[subIdx];

                    // 创建 handler，并注册到 subReactor 的 selector
                    EchoHandler handler = new EchoHandler(client) {};
                    // ⚠️ 关键：跨线程注册前必须 wakeup，否则注册会阻塞到 subReactor 唤醒
                    subSel.wakeup();
                    client.register(subSel, SelectionKey.OP_READ, handler);
                    System.out.println("[master-slave] new client " + client.getRemoteAddress()
                            + " -> sub-reactor-" + subIdx);
                }
            }
        }
    }

    /**
     * subReactor 事件循环：与 SingleReactorServer 主循环几乎一致，
     * 但<b>永远</b>不会处理 accept 事件（mainReactor 已分流）。
     *
     * <h2>为什么这里多 try/catch</h2>
     * 业务异常不应让整个 subReactor 线程挂掉；
     * 抓异常后 {@code key.cancel()} 让 selector 释放这个 key。
     */
    private static void runSubLoop(Selector selector) {
        try {
            while (selector.select() > 0 || !Thread.interrupted()) {
                Set<SelectionKey> ready = selector.selectedKeys();
                Iterator<SelectionKey> it = ready.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    // subReactor 只需处理 read；其它忽略
                    if (!key.isReadable() || !key.isValid()) {
                        continue;
                    }
                    EchoHandler handler = (EchoHandler) key.attachment();
                    try {
                        handler.onRead();
                    } catch (Exception e) {
                        System.err.println("[master-slave] handler error: " + e.getMessage());
                        key.cancel();   // 出错就取消 key，避免持续触发坏 channel
                    }
                }
            }
        } catch (IOException e) {
            // selector 自身 IO 错误（如 wakeup 冲突），整个子 Reactor 退出
            System.err.println("[master-slave] sub loop error: " + e.getMessage());
        }
    }
}
