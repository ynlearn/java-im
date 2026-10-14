package com.teachlearn.im.mini;

import com.teachlearn.im.mini.codec.ImFrameDecoder;
import com.teachlearn.im.mini.codec.ImFrameEncoder;
import com.teachlearn.im.mini.protocol.ImCommand;
import com.teachlearn.im.mini.protocol.MessageType;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * 第 7 章：mini IM 命令行客户端。
 *
 * <h2>本类的核心目的</h2>
 * 与 {@link ImServer} 对接的命令行 IM 客户端，便于在终端快速验证 mini IM 的能力。
 *
 * <h2>4 个命令（重点记忆）</h2>
 * <ul>
 *     <li>{@code login alice} —— 登录（必须先登录才能发消息）</li>
 *     <li>{@code @bob hello} —— 给 bob 发单聊</li>
 *     <li>{@code @all hi} —— 群发</li>
 *     <li>{@code bye} —— 退出</li>
 * </ul>
 *
 * <h2>Pipeline 顺序（与服务端对齐）</h2>
 * <pre>
 * TCP 字节 → ImFrameDecoder
 *         → ImFrameEncoder
 *         → IdleStateHandler(20s 写空闲 → 发 PING)
 *         → SimpleChannelInboundHandler<ImCommand>  (打印所有消息)
 * </pre>
 *
 * <h2>2 个状态（重要）</h2>
 * <ul>
 *     <li>{@code channel} —— Netty 连接到服务端的 socket</li>
 *     <li>{@code username} —— 登录后的用户名（没登录不允许发消息）</li>
 * </ul>
 */
public final class ImClient {

    /**
     * 默认端口。
     * <p>不引用 {@link ImServer#DEFAULT_PORT} 是因为 im-client 是单独模块，
     * 跨模块常量会让"模块边界"复杂化。本地写死 9100 与服务端一致即可。
     */
    public static final int DEFAULT_PORT = 9100;

    private final String host;
    private final int port;
    private final EventLoopGroup group = new NioEventLoopGroup();
    private Channel channel;
    private String username;

    public ImClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        new ImClient(host, port).start();
    }

    /**
     * 启动：先 connect，再主循环读 stdin。
     */
    private void start() throws Exception {
        connect();
        // 主循环：stdin 解析为命令
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if ("bye".equalsIgnoreCase(line)) {
                    // bye：主动关 channel（触发服务端 channelInactive）
                    if (channel != null) {
                        channel.close().sync();
                    }
                    break;
                }
                if (line.startsWith("login ")) {
                    // login alice → 拆出 username 调 login()
                    String name = line.substring(6).trim();
                    login(name);
                } else if (line.startsWith("@all ")) {
                    // @all msg → 群发
                    sendGroup(line.substring(5));
                } else if (line.startsWith("@")) {
                    // @user msg → 单聊
                    int sp = line.indexOf(' ');
                    if (sp < 0) {
                        System.out.println("用法：@user 消息内容");
                        continue;
                    }
                    String to = line.substring(1, sp);
                    String body = line.substring(sp + 1);
                    sendChat(to, body);
                } else {
                    System.out.println("未知命令：" + line);
                    System.out.println("用法：login <name> / @<user> <msg> / @all <msg> / bye");
                }
            }
        }
        // 离开 try 块：关闭 EventLoopGroup（channel 之前已 close）
        group.shutdownGracefully();
    }

    /**
     * 建立 TCP 连接 + 装配 Pipeline。
     * <p>与服务端的关键差异：客户端用 1 个 EventLoopGroup（不像服务端的 boss+worker）；
     * 心跳方向相反——客户端 20s 写空闲时主动 PING（让服务端知道我们还活着）。
     */
    private void connect() throws InterruptedException {
        Bootstrap b = new Bootstrap();
        b.group(group)
         .channel(NioSocketChannel.class)
         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 // 同样不预置 length 字段
                 ch.pipeline().addLast(
                         new ImFrameDecoder(),
                         new ImFrameEncoder(),
                         new IdleStateHandler(20, 0, 0, TimeUnit.SECONDS),
                         new SimpleChannelInboundHandler<ImCommand>() {
                             @Override
                             protected void channelRead0(ChannelHandlerContext ctx, ImCommand msg) {
                                 // 打印所有收到的 ImCommand
                                 System.out.println("[recv] " + msg);
                             }

                             @Override
                             public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                 if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
                                     // 20s 写空闲：发 PING
                                     ctx.writeAndFlush(new ImCommand(MessageType.PING, username, "server", ""));
                                 }
                             }

                             @Override
                             public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                 System.err.println("[err] " + cause.getMessage());
                                 ctx.close();
                             }
                         });
             }
         });

        ChannelFuture f = b.connect(host, port);
        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                System.out.println("[im-client] connected to " + host + ":" + port);
            } else {
                System.err.println("[im-client] connect failed: " + future.cause().getMessage());
            }
        });
        // 阻塞等连接成功
        channel = f.sync().channel();
    }

    /**
     * 登录：发 LOGIN 命令。
     * <p>服务端收到后回 LOGIN_ACK，并批量推 OFFLINE 消息。
     */
    private void login(String name) {
        if (channel == null || !channel.isActive()) {
            System.err.println("[im-client] not connected");
            return;
        }
        // 先在本地记录 username（userEventTriggered 也要用）
        username = name;
        channel.writeAndFlush(new ImCommand(MessageType.LOGIN, name, "server", ""));
    }

    /**
     * 单聊：必须在 login 后调用。
     */
    private void sendChat(String to, String body) {
        if (username == null) {
            System.err.println("[im-client] please login first");
            return;
        }
        channel.writeAndFlush(new ImCommand(MessageType.CHAT, username, to, body));
    }

    /**
     * 群聊：把 to 字段设为 "*"（{@link com.teachlearn.im.mini.protocol.Protocol#GROUP_TARGET}）。
     */
    private void sendGroup(String body) {
        if (username == null) {
            System.err.println("[im-client] please login first");
            return;
        }
        channel.writeAndFlush(new ImCommand(MessageType.GROUP_CHAT, username, "*", body));
    }
}
