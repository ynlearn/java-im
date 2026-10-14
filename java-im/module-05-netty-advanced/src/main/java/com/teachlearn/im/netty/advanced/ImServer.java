package com.teachlearn.im.netty.advanced;

import com.teachlearn.im.netty.advanced.codec.ImMessageDecoder;
import com.teachlearn.im.netty.advanced.codec.ImMessageEncoder;
import com.teachlearn.im.netty.advanced.handler.HeartbeatHandler;
import com.teachlearn.im.netty.advanced.handler.ImServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * 第 5 章：使用协议帧 + 心跳的 IM 服务端。
 *
 * <h2>本类的核心目的</h2>
 * 与 {@code NettyChatServer} 的差异是——<b>应用层协议 + 心跳检测</b>。
 * 教学意义：从"明文换行"升级到"自定协议帧 + 应用层心跳"。
 *
 * <h2>Pipeline 装配（重点记忆）</h2>
 * <pre>
 * Bytes → LengthFieldBasedFrameDecoder   (切半包/粘包)
 *      → ImMessageDecoder                (字节 → ImMessage)
 *      → ImMessageEncoder                (ImMessage → 字节)
 *      → IdleStateHandler                (10s 读空闲触发)
 *      → HeartbeatHandler                (处理 PING/PONG + 主动 PING)
 *      → ImServerHandler                 (业务：广播)
 * </pre>
 * 与客户端的 1 个差别：服务端用"读空闲 10s"作为心跳判据——客户端不发数据就视为"假死"。
 *
 * <h2>3 步启动流程（与 NettyEchoServer 一致）</h2>
 * <ol>
 *     <li>创建两个 EventLoopGroup（boss + worker）</li>
 *     <li>配置 ServerBootstrap（channel、option、childHandler）</li>
 *     <li>{@code bind().sync()} 同步等 bind 成功，{@code closeFuture().sync()} 等关闭</li>
 * </ol>
 */
public final class ImServer {

    /** 默认端口。9098 = 第 5 章 IM 服务端。 */
    public static final int DEFAULT_PORT = 9098;

    /** 工具类不允许实例化。 */
    private ImServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

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
                     // Pipeline 装配：严格按数据流向（上游 → 下游）
                     ch.pipeline().addLast(
                             // 1) 半包/粘包处理：按 length 字段切完整帧
                             new LengthFieldBasedFrameDecoder(1024, 0, 4, -8, 0),
                             // 2) 自定义协议解码
                             new ImMessageDecoder(),
                             // 3) 自定义协议编码
                             new ImMessageEncoder(),
                             // 4) 读空闲检测：10 秒没收到数据视为空闲
                             new IdleStateHandler(10, 0, 0, TimeUnit.SECONDS),
                             // 5) 心跳：处理 PING/PONG + 在空闲时主动 PING
                             HeartbeatHandler.INSTANCE,
                             // 6) 业务 handler
                             new ImServerHandler());
                 }
             });

            ChannelFuture f = b.bind(port).sync();
            System.out.println("[im-server] listening on port " + port);
            f.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
