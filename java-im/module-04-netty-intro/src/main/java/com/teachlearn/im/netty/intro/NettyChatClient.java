package com.teachlearn.im.netty.intro;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 第 4 章：群聊客户端（命令行版）。
 *
 * <h2>本类的核心目的</h2>
 * 测试 {@link NettyChatServer} 的命令行客户端：
 * <ul>
 *     <li>用 {@link Bootstrap}（不是 ServerBootstrap）启动客户端</li>
 *     <li>从 stdin 读行，写到 channel；同时异步打印来自服务端的消息</li>
 *     <li>多开几个窗口就能看到群聊效果</li>
 * </ul>
 *
 * <h2>与服务端的 3 个差异（与 NettyEchoClient 类似的对比）</h2>
 * <ol>
 *     <li>用 {@link Bootstrap}（不是 ServerBootstrap）</li>
 *     <li>只有 1 个 EventLoopGroup（不是 2 个）</li>
 *     <li>用 {@link NioSocketChannel}（不是 NioServerSocketChannel）</li>
 * </ol>
 */
public final class NettyChatClient {

    /**
     * 默认端口。直接复用 {@link NettyChatServer} 端口常量（避免硬编码两份）。
     */
    public static final int DEFAULT_PORT = NettyChatServer.DEFAULT_PORT;

    /** 工具类不允许实例化。 */
    private NettyChatClient() {}

    public static void main(String[] args) throws Exception {
        // 命令行格式：java NettyChatClient [host] [port]
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        // 客户端只需 1 个 EventLoopGroup（一个线程组既处理 IO 也处理业务）
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
             .channel(NioSocketChannel.class)
             .option(ChannelOption.TCP_NODELAY, true)   // 关 Nagle（实时聊天的关键）
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     // 客户端只读，不需要 @Sharable，每个连接用匿名内部类即可
                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                         @Override
                         public void channelRead(ChannelHandlerContext ctx, Object msg) {
                             // 直接打印服务端发来的消息
                             ByteBuf in = (ByteBuf) msg;
                             System.out.println(in.toString(StandardCharsets.UTF_8).trim());
                         }

                         @Override
                         public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                             cause.printStackTrace();
                             ctx.close();
                         }
                     });
                 }
             });

            // 同步连接（connect 是异步的；sync 阻塞到成功）
            ChannelFuture f = b.connect(host, port).sync();
            Channel channel = f.channel();
            System.out.println("[netty-chat-client] connected to " + host + ":" + port);

            // 主线程读 stdin → 写 channel；服务端发来的消息由 EventLoop 线程异步打印
            try (Scanner scanner = new Scanner(System.in)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if ("bye".equalsIgnoreCase(line)) {
                        // break → channel.close() → 服务端 channelInactive
                        break;
                    }
                    // writeAndFlush 异步发送
                    channel.writeAndFlush(Unpooled.wrappedBuffer(
                            (line + "\n").getBytes(StandardCharsets.UTF_8)));
                }
            }
            // 等服务端关闭连接
            channel.closeFuture().sync();
        } finally {
            // 优雅关闭
            group.shutdownGracefully();
        }
    }
}
