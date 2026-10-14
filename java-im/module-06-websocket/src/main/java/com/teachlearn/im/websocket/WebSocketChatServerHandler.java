package com.teachlearn.im.websocket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 6 章：WebSocket 业务 handler。
 *
 * <h2>本类的核心目的</h2>
 * 接收浏览器发来的 WebSocket 文本帧，广播给所有在线连接。
 *
 * <h2>WebSocket 帧类型（必背）</h2>
 * <ul>
 *     <li>{@link TextWebSocketFrame} —— 文本帧（最常用，聊天、JSON）</li>
 *     <li>BinaryWebSocketFrame —— 二进制帧（图片、文件）</li>
 *     <li>CloseWebSocketFrame —— 关闭帧（{@code WebSocketServerProtocolHandler} 内部处理）</li>
 *     <li>PingWebSocketFrame / PongWebSocketFrame —— 心跳（同上）</li>
 *     <li>ContinuationWebSocketFrame —— 分片帧（教学不展开）</li>
 * </ul>
 *
 * <h2>本类只处理文本帧</h2>
 * 其它帧类型（ping/pong/close）已由 {@code WebSocketServerProtocolHandler} 内部消化。
 * 这是 Netty 设计上的"职责分离"：本类只关心业务。
 */
@ChannelHandler.Sharable
public class WebSocketChatServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    /** 在线 channel 集合（用 ConcurrentHashMap 防并发）。 */
    private final Map<Channel, String> online = new ConcurrentHashMap<>();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 新建连接（WebSocket 握手已完成）：加入集合 + 广播加入消息
        online.put(ctx.channel(), ctx.channel().remoteAddress().toString());
        broadcast("[system] " + ctx.channel().remoteAddress() + " joined");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // 只处理文本帧
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame text = (TextWebSocketFrame) frame;
            String msg = text.text();
            // 广播：附上发送者地址
            broadcast("[" + ctx.channel().remoteAddress() + "] " + msg);
        } else {
            // 其它帧类型（binary、continuation 等）：教学不演示
            System.out.println("[ws-chat] ignore non-text frame: " + frame.getClass().getSimpleName());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        online.remove(ctx.channel());
        broadcast("[system] " + ctx.channel().remoteAddress() + " left");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[ws-chat] error: " + cause.getMessage());
        ctx.close();
    }

    /**
     * 广播文本帧。
     * <p>注意：每次 new 一个 TextWebSocketFrame 是因为 Broadcast 给多个 channel，
     * 同一个 frame 共享会出现 index 错乱。
     * 真实生产中可以用 Netty 的 {@code io.netty.handler.codec.http.websocketx.WebSocketFrame.retain()}
     * + duplicate 来共享，但 new 更安全直观。
     */
    private void broadcast(String text) {
        TextWebSocketFrame frame = new TextWebSocketFrame(text);
        for (Channel ch : online.keySet()) {
            ch.writeAndFlush(frame);
        }
    }
}
