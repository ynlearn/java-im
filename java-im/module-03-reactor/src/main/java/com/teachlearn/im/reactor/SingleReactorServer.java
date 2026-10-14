package com.teachlearn.im.reactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * 第 3 章 demo：单 Reactor + 单线程。
 *
 * <h2>本类的核心目的</h2>
 * 在第 2 章 NIO 模板之上引入 <b>Reactor 模式</b>的第一个形态——
 * <b>单 Reactor 单线程</b>：1 个 Selector + 1 个线程同时处理 accept 与所有连接的 read。
 *
 * <h2>Reactor 模式（重点记忆）</h2>
 * <ul>
 *     <li><b>Reactor（反应器）</b>：一个 Selector 循环 select()/dispatch()，对应本类主循环</li>
 *     <li><b>Handler（处理器）</b>：每个连接一个，封装"读 → 解码 → 业务 → 写"，
 *         对应 {@link EchoHandler}</li>
 *     <li><b>Demultiplexer（多路复用器）</b>：底层就是 JDK 的 {@link Selector}</li>
 * </ul>
 *
 * <h2>单 Reactor 单线程的两个固有缺陷</h2>
 * <ol>
 *     <li><b>业务阻塞即全局阻塞</b>：业务代码在 Reactor 线程上跑，
 *         任何阻塞（如 DB 查询、慢业务）都会让所有连接 hang 住。</li>
 *     <li><b>多核 CPU 利用率低</b>：一个线程最多占 1 个核，8 核机器只能用到 1/8。</li>
 * </ol>
 * 这两个缺陷被 {@link MasterSlaveReactorServer} 和 Netty 的 EventLoopGroup 解决。
 *
 * <h2>本章刻意保留的简化点</h2>
 * <ul>
 *     <li>半包处理未做（参考第 2 章 demo 注释）</li>
 *     <li>未注册 OP_WRITE：大消息回写时仍可能阻塞</li>
 *     <li>未做断连清理：仅靠 client.close() 兜底</li>
 * </ul>
 */
public final class SingleReactorServer {

    /**
     * 默认端口。
     * <ul>
     *     <li>9090 = baseline，9091/9092 = BIO 系列，9093 = NIO，9094 = 单 Reactor</li>
     *     <li>9095 = 主从 Reactor（见 MasterSlaveReactorServer）</li>
     * </ul>
     */
    public static final int DEFAULT_PORT = 9094;

    /** 工具类不允许实例化。 */
    private SingleReactorServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /**
     * 主循环：单 Selector 处理 accept + read 两类事件。
     *
     * <h2>关键点：handler 通过 attachment 传递</h2>
     * 客户端连接 accept 之后，创建一个 {@link EchoHandler} 实例，
     * 并通过 {@code SelectionKey.attach(handler)} 绑定到 key 上。
     * 后续 read 事件触发时，从 {@code key.attachment()} 取出 handler 调用。
     * 这就是 Reactor "dispatch 到 handler" 的最简实现。
     */
    private static void start(int port) throws IOException {
        try (Selector selector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open()) {

            server.bind(new InetSocketAddress(port));
            server.configureBlocking(false);
            // 服务端 channel 只关心"新连接接入"
            server.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("[single-reactor] listening on port " + port);
            System.out.println("[single-reactor] model: 1 selector + 1 thread (also handles IO) ");

            while (selector.select() > 0 || !Thread.interrupted()) {
                Set<SelectionKey> ready = selector.selectedKeys();
                Iterator<SelectionKey> it = ready.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();   // 必须手动 remove（参见 NioEchoServer 注释）
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        // 分支 1：accept 新连接
                        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                        SocketChannel client = ssc.accept();
                        client.configureBlocking(false);
                        // 给每个连接创建一个 handler，挂到 key 的 attachment
                        // abstract class 用匿名子类的方式 "实例化"
                        EchoHandler handler = new EchoHandler(client) {};
                        client.register(selector, SelectionKey.OP_READ, handler);
                        System.out.println("[single-reactor] client connected: " + client.getRemoteAddress());
                    } else if (key.isReadable()) {
                        // 分支 2：read 事件 → 分发到 handler
                        EchoHandler handler = (EchoHandler) key.attachment();
                        handler.onRead();
                    }
                }
            }
        }
    }
}
