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
 * 第 4 章 demo：Netty echo 服务端（最简骨架）。
 *
 * <h2>本类的核心目的</h2>
 * 用 Netty 重写第 2 章 NIO echo 服务端。代码量从 ~150 行降到 ~30 行，
 * 能力反而更强——Netty 把"主从 Reactor、Channel 生命周期、Pipeline、Handler 调度"
 * 全部封装好。本类是整个 Netty 体系的入口。
 *
 * <h2>对应主从 Reactor 模型（必背）</h2>
 * <ul>
 *     <li>{@code bossGroup} = mainReactor，1 个线程，只处理 OP_ACCEPT</li>
 *     <li>{@code workerGroup} = subReactorGroup，默认 = 2 × CPU 核数，处理 OP_READ/OP_WRITE</li>
 *     <li>{@link ChannelInitializer} 在新连接建立时把 {@link NettyEchoHandler} 加入 pipeline</li>
 *     <li>每个连接上的所有 IO 都由 workerGroup 中的某一个 EventLoop 线程处理（<b>串行化保证</b>）</li>
 * </ul>
 *
 * <h2>5 个关键步骤（建议背诵）</h2>
 * <ol>
 *     <li>{@code new NioEventLoopGroup(1)} 创建 boss group</li>
 *     <li>{@code new NioEventLoopGroup()} 创建 worker group（默认 2×CPU）</li>
 *     <li>{@code new ServerBootstrap()} 装配：group / channel / option / childHandler</li>
 *     <li>{@code bind(port).sync()} 同步等 bind 成功（netty 异步 API 的"半同步"惯用法）</li>
 *     <li>{@code f.channel().closeFuture().sync()} 阻塞等服务端 channel 关闭</li>
 * </ol>
 */
public final class NettyEchoServer {

    /** 默认端口。9094=单 Reactor，9095=主从 Reactor，9096=Netty echo。 */
    public static final int DEFAULT_PORT = 9096;

    /** 工具类不允许实例化。 */
    private NettyEchoServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /**
     * 标准的 Netty 服务端启动流程。
     *
     * <h2>几个 option / childOption 的含义（重点记忆）</h2>
     * <ul>
     *     <li>{@code ChannelOption.SO_BACKLOG=128} —— 服务端 socket 内核 accept 队列长度；
     *         与 {@code serverSocket.setReceiveBufferSize} 不同，是"未完成握手"队列</li>
     *     <li>{@code ChannelOption.SO_KEEPALIVE=true} —— TCP 心跳，
     *         操作系统层定期发探测包识别对端存活（与应用层 IdleState 不同）</li>
     *     <li>{@code ChannelOption.TCP_NODELAY=true} —— 关闭 Nagle 算法，
     *         小消息立即发，对实时通讯至关重要</li>
     * </ul>
     *
     * <h2>为什么 channel() 和 childHandler() 是分开的</h2>
     * <ul>
     *     <li>{@code channel(NioServerSocketChannel.class)} —— 服务端监听 channel（仅 1 个）</li>
     *     <li>{@code childHandler(...)} —— 每个新连接 child channel 的 handler</li>
     *     <li>option 是给前者；childOption 是给后者</li>
     * </ul>
     */
    private static void start(int port) throws Exception {
        // boss = 1 个线程，专职 accept；worker = 默认 2*CPU，专职 IO
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            // 启动器：把上面所有"零件"装配到 ServerBootstrap
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)                                  // ① 装配两个 EventLoopGroup
             .channel(NioServerSocketChannel.class)                         // ② 选用 NIO 服务端 channel 实现
             .option(ChannelOption.SO_BACKLOG, 128)                          // ③ 服务端 socket 选项
             .childOption(ChannelOption.SO_KEEPALIVE, true)                  // ④ 客户端 socket 选项
             .childOption(ChannelOption.TCP_NODELAY, true)                   // ⑤ 关 Nagle，小消息立即发
             .childHandler(new ChannelInitializer<SocketChannel>() {        // ⑥ 子 channel 初始化器
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     // pipeline 是责任链；新连接一建立就把 echo handler 装进去
                     ch.pipeline().addLast(new NettyEchoHandler());
                 }
             });

            // 绑定端口并同步等待（bind 实际是异步的；sync 阻塞到 bind 完成）
            ChannelFuture f = b.bind(port).sync();
            System.out.println("[netty-echo] listening on port " + port);
            // 阻塞直到服务端 channel 关闭（Ctrl+C / kill 都会触发）
            f.channel().closeFuture().sync();
        } finally {
            // 优雅关闭两个 EventLoopGroup，释放线程和 fd
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
