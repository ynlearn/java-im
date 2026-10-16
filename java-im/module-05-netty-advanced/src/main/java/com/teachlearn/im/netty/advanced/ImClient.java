package com.teachlearn.im.netty.advanced;

import com.teachlearn.im.netty.advanced.codec.ImMessageDecoder;
import com.teachlearn.im.netty.advanced.codec.ImMessageEncoder;
import com.teachlearn.im.netty.advanced.handler.HeartbeatHandler;
import com.teachlearn.im.netty.advanced.handler.ImClientHandler;
import com.teachlearn.im.netty.advanced.protocol.ImMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * 第 5 章：带心跳 + 自动重连的 IM 客户端。
 *
 * <h2>本类的核心目的</h2>
 * 演示 IM 客户端的"两条新能力"——
 * <ul>
 *     <li><b>心跳</b>：3s 写空闲主动发 PING，防止被服务端判为读空闲</li>
 *     <li><b>重连</b>：连接断开后按指数退避（1s → 2s → 4s → 8s → 16s）自动重试，最多 5 次</li>
 * </ul>
 *
 * <h2>Pipeline 顺序（重要）</h2>
 * <pre>
 * Bytes → LengthFieldBasedFrameDecoder   (半包)
 *      → ImMessageDecoder                (字节→POJO)
 *      → ImMessageEncoder                (POJO→字节，反向时用)
 *      → IdleStateHandler                (3s 写空闲触发)
 *      → HeartbeatHandler                (处理 PING/PONG)
 *      → ImClientHandler                 (业务：打印)
 * </pre>
 *
 * <h2>与 NettyChatClient 的 3 个增强</h2>
 * <ol>
 *     <li>加了协议帧（LengthField + ImMessage 编解码）</li>
 *     <li>加了 IdleStateHandler + HeartbeatHandler</li>
 *     <li>加了 connect 失败的指数退避重连（生产 IM 客户端必备）</li>
 * </ol>
 */
public final class ImClient {

    /**
     * 默认端口：复用 {@link ImServer} 的端口常量（避免硬编码）。
     */
    public static final int DEFAULT_PORT = ImServer.DEFAULT_PORT;

    /** 最大重连次数：超过后放弃（防止无限重连耗资源）。 */
    private static final int MAX_RECONNECT = 5;

    private final String host;
    private final int port;
    private EventLoopGroup group;
    private Channel channel;
    /** 已重连次数（用于计算指数退避）。 */
    private int reconnectAttempt = 0;

    public ImClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        // 命令行：java ImClient [host] [port]
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        new ImClient(host, port).start();
    }

    /**
     * 启动：先 connect，然后主循环读 stdin。
     */
    private void start() throws Exception {
        connect();
        // 主线程读 stdin → 写 channel
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if ("bye".equalsIgnoreCase(line)) {
                    break;
                }
                // 注意：channel 可能因为网络抖动暂时 null/不 active，要先判断
                if (channel != null && channel.isActive()) {
                    channel.writeAndFlush(new ImMessage(line));
                }
            }
        }
        // 收尾：关 channel 和 EventLoopGroup
        if (channel != null) {
            channel.close().sync();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    /**
     * 启动一次连接尝试。
     * 成功：完成首次连接；失败：进入重连。
     */
    private void connect() throws InterruptedException {
        group = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(group)
         .channel(NioSocketChannel.class)
         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)   // 连接超时 3s
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 // Pipeline 装配：协议帧 → 编解码 → 空闲检测 → 心跳 → 业务
                 ch.pipeline().addLast(
                         // 1) 切半包/粘包：length 字段在 magic 后面（offset=4）
                         //    length 只算 body 字节数，帧总长 = magic(4) + length(4) + body，
                         //    所以 lengthAdjustment = 0（不需要补偿）
                         new LengthFieldBasedFrameDecoder(1024, 4, 4, 0, 0),
                         // 2) 字节 → ImMessage
                         new ImMessageDecoder(),
                         // 3) ImMessage → 字节
                         new ImMessageEncoder(),
                         // 4) 客户端 3s 写空闲（无下行数据时发 PING）
                         new IdleStateHandler(0, 3, 0, TimeUnit.SECONDS),
                         // 5) 心跳：处理 PING/PONG
                         HeartbeatHandler.INSTANCE,
                         // 6) 业务：打印
                         new ImClientHandler());
             }
         });

        // connect 异步
        ChannelFuture f = b.connect(host, port);
        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                System.out.println("[im-client] connected to " + host + ":" + port);
                // 成功：重置重连计数
                reconnectAttempt = 0;
            } else {
                // 失败：进入指数退避重连
                System.err.println("[im-client] connect failed: " + future.cause().getMessage());
                scheduleReconnect();
            }
        });
        try {
            channel = f.sync().channel();
        } catch (Exception e) {
            // 启动期连接失败：进入重连，main 线程先阻塞让重连线程接管
            scheduleReconnect();
            Thread.currentThread().join();
        }
    }

    /**
     * 指数退避重连。
     * <p>公式：delay = 2^reconnectAttempt（秒）
     * <ul>
     *     <li>第 1 次：1s 后重试</li>
     *     <li>第 2 次：2s</li>
     *     <li>第 3 次：4s</li>
     *     <li>...</li>
     * </ul>
     * 关键：必须用 EventLoopGroup 的 schedule 提交（而不是 new ScheduledExecutorService），
     * 因为重连要复用同一组 EventLoop 资源。
     */
    private void scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT) {
            System.err.println("[im-client] reached max reconnect attempts, giving up");
            return;
        }
        // 1 << reconnectAttempt：0→1, 1→2, 2→4, 3→8, 4→16
        int delay = 1 << reconnectAttempt;
        reconnectAttempt++;
        System.out.println("[im-client] reconnecting in " + delay + "s (attempt " + reconnectAttempt + ")");
        // 把重连任务提交到 EventLoop 线程组（注意是 group，不是 workerGroup）
        group.schedule(() -> {
            try {
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, delay, TimeUnit.SECONDS);
    }
}
