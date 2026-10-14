package com.teachlearn.im.netty.intro;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 第 4 章 demo：Netty 群聊服务端。
 *
 * <h2>本类的核心目的</h2>
 * 在 echo 基础上加"广播"能力：所有连接到服务端的客户端互相能看到对方的消息。
 * 这是第 7 章 mini IM "群聊"的最小雏形。
 *
 * <h2>关键设计：单例 + @Sharable</h2>
 * 与 {@link NettyEchoServer} 不同——这里需要"所有连接共享同一个 handler 实例"，
 * 因为要维护共享的"在线 channel 集合"。
 * <ul>
 *     <li>{@code NettyChatServer.HANDLER} —— 静态单例，所有 childHandler 都用同一个</li>
 *     <li>handler 上加 {@code @ChannelHandler.Sharable} —— Netty 用它检查是否允许共享</li>
 * </ul>
 *
 * <h2>本 demo 的简化点</h2>
 * <ul>
 *     <li>无登录鉴权（@Sharable 是为了让 handler 持有"在线集合"，不是安全设计）</li>
 *     <li>无昵称（用 remoteAddress 当昵称）</li>
 *     <li>无协议（明文 + \n，参考第 5 章扩展为 LengthField 帧）</li>
 * </ul>
 */
public final class NettyChatServer {

    /** 默认端口。9097 = 群聊。 */
    public static final int DEFAULT_PORT = 9097;

    /**
     * 共享 handler 单例。
     * <p>注意：必须与 {@link NettyChatServerHandler#online} 配套使用
     * （handler 内部维护在线 channel map）。
     * 如果改成"每个连接一个 handler 实例"，map 就只看到自己一个 channel，群聊失效。
     */
    public static final NettyChatServerHandler HANDLER = new NettyChatServerHandler();

    /** 工具类不允许实例化。 */
    private NettyChatServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /**
     * 与 NettyEchoServer 几乎一致，唯一区别：
     * <ul>
     *     <li>{@code childHandler} 中用 {@link #HANDLER}（共享）而非 new</li>
     *     <li>没设 {@code SO_BACKLOG}（教学简化）</li>
     * </ul>
     */
    private static void start(int port) throws Exception {
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
             .channel(NioServerSocketChannel.class)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     // 关键：addLast(HANDLER) 而不是 addLast(new NettyChatServerHandler())
                     ch.pipeline().addLast(HANDLER);
                 }
             });

            ChannelFuture f = b.bind(port).sync();
            System.out.println("[netty-chat] listening on port " + port);
            f.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
