package com.teachlearn.im.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

/**
 * 第 2 章 demo：NIO echo 服务端。
 *
 * <h2>本类的核心目的</h2>
 * 用 JDK NIO 三大件 —— {@link Selector} / {@link ServerSocketChannel} /
 * {@link SocketChannel} / {@link ByteBuffer} —— 实现<b>单线程多连接</b>的 echo 服务。
 * 这是从"每连接一线程"BIO 跨到"单线程多路复用"的关键一步，也是后续 Reactor 与 Netty 的基础。
 *
 * <h2>NIO 三大件速记（重点）</h2>
 * <ul>
 *     <li><b>Channel（通道）</b>：代表一个 IO 通道。比 BIO 的 Stream 更强大——可以同时读写
 *         （全双工），可以注册到 Selector。</li>
 *     <li><b>Buffer（缓冲）</b>：NIO 的所有 IO 都要先经过 Buffer。核心方法：{@code flip/clear/compact}。</li>
 *     <li><b>Selector（多路复用器）</b>：底层封装 epoll/kqueue，一个 Selector 可以管理 N 个 Channel。
 *         关键调用：{@code select()} 阻塞等到至少一个 channel 有事件。</li>
 * </ul>
 *
 * <h2>本章的 3 个"故意缺陷"（教学意义）</h2>
 * <ol>
 *     <li><b>没有半包处理</b>：假设每次 read() 都能读到完整一行；真实场景必须用
 *         {@code LengthFieldBasedFrameDecoder}（第 5 章展开）。</li>
 *     <li><b>没有异步写</b>：write() 在大消息时可能阻塞 Selector，应注册 OP_WRITE 异步写。</li>
 *     <li><b>业务处理在 Selector 线程</b>：CPU 密集型业务会阻塞所有连接的 IO（第 4 章 Netty 通过
 *         worker 线程组解决）。</li>
 * </ol>
 */
public final class NioEchoServer {

    /** 默认端口。9090=基线，9091=BIO，9092=伪异步 BIO，9093=NIO。 */
    public static final int DEFAULT_PORT = 9093;

    /** 工具类不允许实例化。 */
    private NioEchoServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /**
     * NIO 服务端主循环（标准模板，建议背下来）。
     *
     * <h2>5 步标准流程</h2>
     * <ol>
     *     <li>{@code Selector.open()} 打开多路复用器（Linux 底层是 epoll_create）</li>
     *     <li>{@code ServerSocketChannel.open()} 创建服务端 channel，
     *         调 {@code configureBlocking(false)} 切非阻塞模式（注册 Selector 的前提）</li>
     *     <li>{@code bind()} 绑定端口，{@code register(selector, OP_ACCEPT)} 注册 accept 事件</li>
     *     <li>循环 {@code select()} 阻塞等到有事件</li>
     *     <li>遍历 selectedKeys()，按事件类型分发：accept / read / write</li>
     * </ol>
     *
     * <h2>为什么"selectedKeys 必须手动 remove"</h2>
     * selectedKeys() 返回的是"已就绪事件"的集合；处理完后必须 iterator.remove()，
     * 否则下一轮 select() 还会拿到同一个 key，导致事件重复处理（事件风暴）。
     */
    private static void start(int port) throws IOException {
        // try-with-resources 自动关闭 Selector（Selector 实现了 AutoCloseable）
        try (Selector selector = Selector.open();
             ServerSocketChannel serverChannel = ServerSocketChannel.open()) {

            serverChannel.bind(new InetSocketAddress(port));
            // 必须切非阻塞！否则 register 会抛 IllegalBlockingModeException
            serverChannel.configureBlocking(false);
            // 服务端 channel 只关心"有新连接进来"事件
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("[nio] echo server listening on port " + port);
            System.out.println("[nio] model: single-threaded Selector multiplexing");

            // 主事件循环
            // select() 返回"已就绪 channel 数"，>0 表示有事件需要处理
            // || Thread.interrupted() 让 Ctrl+C 能优雅退出
            while (selector.select() > 0 || Thread.interrupted()) {
                Set<SelectionKey> readyKeys = selector.selectedKeys();
                Iterator<SelectionKey> it = readyKeys.iterator();

                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();   // ⚠️ 必须手动移除，否则下次 select 仍会拿到

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        handleAccept(key, selector);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    }
                    // write 一般采用"半包写"或注册 OP_WRITE 异步写，本 demo 省略
                }
            }
        }
    }

    /**
     * 处理新连接：accept 客户端 socket，并注册到同一个 Selector 上关注 OP_READ。
     */
    private static void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel client = serverChannel.accept();
        // 客户端 socket 也必须切非阻塞
        client.configureBlocking(false);
        // 注册读事件
        client.register(selector, SelectionKey.OP_READ);
        System.out.println("[nio] client connected: " + client.getRemoteAddress());
    }

    /**
     * 处理读事件：read 字节 → 解码为字符串 → 直接 write 回写（简化版 echo）。
     *
     * <h2>本章的"半包风险"</h2>
     * 如果客户端一帧超过 256 字节（ByteBuffer 容量），会被截断；如果一帧分两次到达，
     * 第一次 read 拿到的就是不完整的字符串。本章不处理，第 5 章用 LengthField 解决。
     */
    private static void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        // 每次 read 都新建一个 256 字节的 ByteBuffer（教学简化）
        // 生产应该用池化（参考 Netty PooledByteBufAllocator）
        ByteBuffer buf = ByteBuffer.allocate(256);

        int read = client.read(buf);
        if (read == -1) {
            // 客户端 FIN（对端关闭连接），我们也要 close 释放 fd
            System.out.println("[nio] client disconnected: " + client.getRemoteAddress());
            client.close();
            return;
        }
        if (read > 0) {
            // flip：写模式 → 读模式（重置 position=0，limit=已写字节数）
            buf.flip();
            String line = StandardCharsets.UTF_8.decode(buf).toString().trim();
            System.out.println("[nio] recv: " + line);
            // 简化版 echo：直接回写（忽略半包问题，本章不展开）
            client.write(ByteBuffer.wrap(("echo: " + line + "\n").getBytes(StandardCharsets.UTF_8)));
        }
    }
}
