package com.teachlearn.im.netty.advanced.handler;

import com.teachlearn.im.netty.advanced.protocol.ImMessage;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 第 5 章：客户端业务 handler。
 *
 * <h2>本类的核心目的</h2>
 * 收到服务端发来的"非心跳"业务消息时直接打印到 System.out。
 * PING/PONG 已被 {@link HeartbeatHandler} 拦截，本类只关心真正的聊天内容。
 */
@ChannelHandler.Sharable
public class ImClientHandler extends SimpleChannelInboundHandler<ImMessage> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImMessage msg) {
        // HeartbeatHandler 已经过滤掉 PING/PONG
        System.out.println("[im-client] <- " + msg.getBody());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[im-client] error: " + cause.getMessage());
        ctx.close();
    }
}
