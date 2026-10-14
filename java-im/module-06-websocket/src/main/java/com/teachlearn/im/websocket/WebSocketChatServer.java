package com.teachlearn.im.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * 第 6 章：Netty WebSocket 服务端。
 *
 * <h2>本类的核心目的</h2>
 * 在第 4 章 Netty echo/chat 基础上加 <b>HTTP → WebSocket 协议升级</b>，
 * 让浏览器通过 {@code ws://} 也能连接进群聊。
 *
 * <h2>HTTP 与 WebSocket 的关系（必背）</h2>
 * <ol>
 *     <li>客户端发起普通 HTTP GET，带 {@code Upgrade: websocket} 头</li>
 *     <li>服务端响应 {@code 101 Switching Protocols}，TCP 连接从 HTTP 升级为 WebSocket</li>
 *     <li>之后双方在同一条 TCP 连接上收发 WebSocket 帧（不再有 HTTP 头）</li>
 * </ol>
 * Netty 用 {@link WebSocketServerProtocolHandler} 内部完成 ② ③，本类只配 Pipeline。
 *
 * <h2>Pipeline 顺序（非常关键，按数据流向）</h2>
 * <pre>
 * TCP 字节 → HttpServerCodec           (HTTP 编解码)
 *         → ChunkedWriteHandler        (大文件分块)
 *         → HttpObjectAggregator(8192) (聚合 FullHttpRequest)
 *         → WebSocketServerProtocolHandler("/ws")  (握手/ping-pong/close)
 *         → WebSocketChatServerHandler             (业务)
 * </pre>
 *
 * <h2>关键参数 / 路径</h2>
 * <ul>
 *     <li>{@code HttpObjectAggregator(8192)} = 单条 HTTP 消息最大 8KB，超过会 413</li>
 *     <li>{@code /ws} = WebSocket 路径，客户端连 {@code ws://host:port/ws}</li>
 *     <li>无 path 的请求会保持 HTTP，本 demo 不处理</li>
 * </ul>
 */
public final class WebSocketChatServer {

    /** 默认端口。9099 = WebSocket。 */
    public static final int DEFAULT_PORT = 9099;

    /**
     * 共享 handler 单例：维护"在线 channel 集合"。
     * <p>必须 @Sharable，参见 NettyChatServerHandler 注释。
     */
    public static final WebSocketChatServerHandler HANDLER = new WebSocketChatServerHandler();

    /** 工具类不允许实例化。 */
    private WebSocketChatServer() {}

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
             .option(ChannelOption.SO_BACKLOG, 128)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     // Pipeline 装配（数据流向：上 → 下）
                     ch.pipeline().addLast(
                             new HttpServerCodec(),                          // 1) HTTP 编解码
                             new ChunkedWriteHandler(),                      // 2) 大文件分块
                             new HttpObjectAggregator(8192),                 // 3) 聚合为 FullHttpRequest
                             new WebSocketServerProtocolHandler("/ws"),      // 4) WebSocket 协议处理
                             HANDLER);                                       // 5) 业务
                 }
             });

            ChannelFuture f = b.bind(port).sync();
            System.out.println("[ws-chat] listening on port " + port);
            System.out.println("[ws-chat] web client: open src/main/resources/web/index.html in browser");
            f.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
