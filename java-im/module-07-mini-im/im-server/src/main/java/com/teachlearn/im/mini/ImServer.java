package com.teachlearn.im.mini;

import com.teachlearn.im.mini.codec.ImFrameDecoder;
import com.teachlearn.im.mini.codec.ImFrameEncoder;
import com.teachlearn.im.mini.handler.MessageHandler;
import com.teachlearn.im.mini.store.OfflineMessageStore;
import com.teachlearn.im.mini.store.UserRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * 第 7 章：mini IM 服务端（贯穿项目入口）。
 *
 * <h2>本类的核心目的</h2>
 * 把第 1-6 章所有能力"装到一台服务"里：TCP + 自定协议 + 心跳 + 登录/单聊/群聊/离线回放。
 *
 * <h2>3 个组件（与 main 中三件事对应）</h2>
 * <ul>
 *     <li>{@link UserRegistry} —— 维护 username → Channel 路由表</li>
 *     <li>{@link OfflineMessageStore} —— 用户离线时暂存消息</li>
 *     <li>{@link MessageHandler} —— 业务核心：登录/单聊/群聊/路由/回放</li>
 * </ul>
 *
 * <h2>Pipeline 顺序（按数据流向）</h2>
 * <pre>
 * TCP 字节 → ImFrameDecoder          (解析为 ImCommand)
 *         → ImFrameEncoder           (写回时用)
 *         → IdleStateHandler(30s)    (读空闲超时关闭)
 *         → MessageHandler           (业务)
 * </pre>
 * <p><b>注意：</b>mini IM 没装 LengthFieldBasedFrameDecoder——
 * 协议格式不预置 length 字段，靠 ImFrameDecoder 用 markReaderIndex/resetReaderIndex 自管半包。
 *
 * <h2>3 步启动流程（与第 5 章 ImServer 一致）</h2>
 * <ol>
 *     <li>建 3 个组件 + 2 个 EventLoopGroup</li>
 *     <li>配置 ServerBootstrap（channel、option、childHandler）</li>
 *     <li>{@code bind().sync()} + {@code closeFuture().sync()}</li>
 * </ol>
 */
public final class ImServer {

    /** 默认端口。9100 = mini IM。 */
    public static final int DEFAULT_PORT = 9100;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    private static void start(int port) throws Exception {
        // 1) 三个"业务组件"：用户注册表、离线消息、业务 handler
        UserRegistry userRegistry = new UserRegistry();
        OfflineMessageStore offlineStore = new OfflineMessageStore();
        MessageHandler messageHandler = new MessageHandler(userRegistry, offlineStore);

        // 2) Netty 标准结构
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
                     // 注释保留教学决策：协议不预置 length 字段
                     ch.pipeline().addLast(
                             new ImFrameDecoder(),                          // 1) 字节 → ImCommand
                             new ImFrameEncoder(),                          // 2) ImCommand → 字节
                             new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS), // 3) 30s 读空闲关闭
                             messageHandler);                               // 4) 业务
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
