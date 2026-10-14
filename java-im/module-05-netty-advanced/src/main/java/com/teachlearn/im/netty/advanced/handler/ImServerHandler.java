package com.teachlearn.im.netty.advanced.handler;

import com.teachlearn.im.netty.advanced.protocol.ImMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 5 章：服务端业务 handler。
 *
 * <h2>本类的核心目的</h2>
 * 收到非心跳业务消息时，<b>转发给所有在线 channel</b>（教学版广播）。
 * 这是 mini IM "群聊"能力的最小雏形；第 7 章会扩展为"按 user 路由 + 离线存储"。
 */
@ChannelHandler.Sharable
public class ImServerHandler extends SimpleChannelInboundHandler<ImMessage> {

    /**
     * 在线 channel 集合。
     * <p>key = Channel，value = 昵称（教学 demo 用 user-N）。
     * 必须 {@link ConcurrentHashMap}：不同 EventLoop 线程会并发读写。
     */
    private final Map<Channel, String> online = new ConcurrentHashMap<>();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 新连接：进入集合，广播
        online.put(ctx.channel(), "user-" + online.size());
        broadcast("[system] " + ctx.channel().remoteAddress() + " joined");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImMessage msg) {
        // HeartbeatHandler 已经过滤掉 PING/PONG
        // 这里的 msg 是真正的聊天内容
        broadcast("[" + ctx.channel().remoteAddress() + "] " + msg.getBody());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        online.remove(ctx.channel());
        broadcast("[system] " + ctx.channel().remoteAddress() + " left");
    }

    /**
     * 广播消息：遍历在线 channel 写入同一份 ImMessage。
     * <p>注意：writeAndFlush(msg) 会自动 retain 一次 msg，所以同一个实例能安全写入多个 channel。
     */
    private void broadcast(String text) {
        ImMessage msg = new ImMessage(text);
        for (Channel ch : online.keySet()) {
            ch.writeAndFlush(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[im-server] error: " + cause.getMessage());
        ctx.close();
    }
}
