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
 * 第 4 章：Netty echo 客户端。
 *
 * <h2>本类的核心目的</h2>
 * 演示 Netty <b>客户端</b>的标准写法，并和服务端 {@link NettyEchoServer} 对照。
 * 与服务端代码差别其实不大，主要是把 {@code ServerBootstrap} 换成 {@link Bootstrap}、
 * 只需要 1 个 {@link NioEventLoopGroup}。
 *
 * <h2>与服务端代码的 3 个关键差异（记忆点）</h2>
 * <ol>
 *     <li><b>引导器</b>：服务端是 {@code ServerBootstrap}（要 bind 端口），客户端是 {@code Bootstrap}（要 connect 远端）。</li>
 *     <li><b>线程组</b>：服务端一般用 boss + worker 两个 Group（对应 mainReactor + subReactor），
 *         客户端只需要 1 个 Group（无 accept 事件，只有 connect/IO）。</li>
 *     <li><b>channel 类</b>：服务端是 {@code NioServerSocketChannel}（监听 server socket），
 *         客户端是 {@code NioSocketChannel}（每个连接一条 socket）。</li>
 * </ol>
 *
 * <h2>为什么用匿名内部类写 handler</h2>
 * 本客户端只关心"收到数据打印"和"出错关闭"，没有跨连接状态，所以直接用
 * {@link ChannelInboundHandlerAdapter} 匿名类即可。如果要"群发"等共享状态，
 * 才需要像 {@link NettyChatServerHandler} 那样抽出去并加 {@code @Sharable}。
 */
public final class NettyEchoClient {

    /**
     * 默认端口：从服务端常量引用，保证客户端和服务端改一处即可同步。
     * 这样改端口时不会"客户端连老端口、服务端起新端口"导致测试不通过。
     */
    public static final int DEFAULT_PORT = NettyEchoServer.DEFAULT_PORT;

    /** 工具类不允许实例化。 */
    private NettyEchoClient() {}

    /**
     * 客户端生命周期（强烈建议按此顺序记忆）：
     * <pre>
     *   1) 配置 Bootstrap（group / channel / option / handler）
     *   2) connect(host, port) —— 异步发起连接
     *   3) sync() 阻塞等待连接成功
     *   4) 在 channel 上读写
     *   5) closeFuture().sync() 阻塞等待服务端关闭连接
     *   6) EventLoopGroup.shutdownGracefully() 释放线程
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        // —— 1) 参数解析 ——
        // 命令行格式：java NettyEchoClient [host] [port]
        // 都不传则默认连本机 9096（NettyEchoServer 的默认端口）。
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        // —— 2) 创建 EventLoopGroup（线程组）——
        // 客户端只需要 1 个 Group：里面所有 EventLoop 共享，负责 connect + IO read/write。
        // 注意：必须在 try 外面定义并在 finally 里 shutdownGracefully()，
        // 否则进程退出时 Netty 的非 daemon 线程会阻止 JVM 退出。
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            // —— 3) 配置 Bootstrap 引导器（客户端与服务端唯一不同的"门面"）——
            Bootstrap b = new Bootstrap();
            b.group(group)
             // channel(...) 指定要创建的"通道类型"：客户端的 socket。
             // 区别于服务端的 NioServerSocketChannel（监听 socket）。
             .channel(NioSocketChannel.class)
             // TCP_NODELAY = true 关闭 Nagle 算法：小包立即发，适合 echo 这种低延迟场景。
             // 通信类系统（IM、游戏）一般都要开；大文件传输反而要关。
             .option(ChannelOption.TCP_NODELAY, true)
             // ChannelInitializer：Netty 的"模板"模式——新连接建好后会调用一次 initChannel。
             // 我们在 initChannel 里把业务 handler 装进 pipeline。
             // 注意：ChannelInitializer 自己会在执行完后从 pipeline 移除（一次性）。
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     // 这里加的 handler 是"每条新连接一份"，所以不需要 @Sharable。
                     // 如果想所有连接共享同一个 handler 实例（带状态），那就在外面
                     // 定义一个常量并加 @Sharable，类似 NettyChatServerHandler。
                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                         @Override
                         public void channelRead(ChannelHandlerContext ctx, Object msg) {
                             // msg 是 ByteBuf（Netty 的字节容器，自带引用计数）
                             ByteBuf in = (ByteBuf) msg;
                             // 直接 toString 把字节解码成字符串。
                             // 因为服务端简单回写 "echo: " + 原内容，客户端只关心打印。
                             // 本章不演示业务解码，第 5 章会拆自定义协议。
                             System.out.println("[netty-echo-client] <- " + in.toString(StandardCharsets.UTF_8));
                             // 注意：read 入站消息后 Netty 会自动 release（如果没人持有引用），
                             // 这里只是 toString 读，没有引用泄漏问题。
                         }

                         @Override
                         public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                             // 任何 handler 里抛出的异常都会汇聚到 exceptionCaught。
                             // 至少要 ctx.close()，否则 channel 资源不会释放。
                             cause.printStackTrace();
                             ctx.close();
                         }
                     });
                 }
             });

            // —— 4) 发起连接（异步）——
            // .connect() 立即返回一个 ChannelFuture，真正连接在 EventLoop 线程里跑。
            // .sync() 会阻塞当前 main 线程直到连接成功（或失败抛异常）。
            ChannelFuture f = b.connect(host, port).sync();
            // 拿到 channel 后就可以 writeAndFlush / close 了
            Channel channel = f.channel();
            System.out.println("[netty-echo-client] connected to " + host + ":" + port);

            // —— 5) 主循环：从 stdin 读用户输入，转成字节写到服务端 ——
            // try-with-resources 保证 Scanner 关闭（不过 System.in 关闭没意义，主要为规整）。
            // 这里有个"内存模型小坑"：Scanner 是阻塞读，而 EventLoop 线程在另外的线程池里跑。
            // 我们在 main 线程写 channel 是"外部线程调用"，Netty 会把 writeAndFlush
            // 自动封装成任务丢进 EventLoop 队列，是线程安全的。
            try (Scanner scanner = new Scanner(System.in)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if ("bye".equalsIgnoreCase(line)) {
                        // bye 是约定的退出命令：跳出循环后走 closeFuture
                        break;
                    }
                    // Unpooled.wrappedBuffer(byte[]) 是最常见的"包字节数组成 ByteBuf"工具方法。
                    // 末尾 + "\n" 与服务端的"按行读"约定对齐（虽然服务端现在不做 readLine 拆行，
                    // 但客户端补换行更通用）。
                    channel.writeAndFlush(Unpooled.wrappedBuffer((line + "\n").getBytes(StandardCharsets.UTF_8)));
                    // writeAndFlush 后 Netty 内部会：1) 编码成 ByteBuf；2) 异步写 socket；
                    // 3) 写完 release 字节容器。对调用方完全无感。
                }
            }
            // —— 6) 等待服务端关闭连接（被动）——
            // 如果你输入 "bye" 跳出循环后想立即退出，可以直接 channel.close()。
            // 这里用 closeFuture().sync() 是"等服务端关闭"——更对称，便于教学演示。
            // 实际生产中通常主动 channel.close()。
            channel.closeFuture().sync();
        } finally {
            // —— 7) 释放资源 ——
            // shutdownGracefully() 会等待所有 pending 任务执行完再退出 EventLoop 线程。
            // 比 shutdownGracefully(0, 0, TimeUnit.SECONDS) 更"温柔"，避免丢消息。
            group.shutdownGracefully();
        }
    }
}
