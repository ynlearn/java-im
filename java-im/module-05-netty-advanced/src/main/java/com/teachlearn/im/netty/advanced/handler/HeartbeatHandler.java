package com.teachlearn.im.netty.advanced.handler;

import com.teachlearn.im.netty.advanced.protocol.ImMessage;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 第 5 章：心跳 handler。
 *
 * <h2>本类的核心目的</h2>
 * 配合 {@code IdleStateHandler} 实现"读空闲超时 → 主动 PING / 关闭连接"。
 * PING/PONG 是应用层心跳，与 TCP KeepAlive（OS 层）独立，更可控。
 *
 * <h2>Pipeline 位置（重要）</h2>
 * <pre>
 * Bytes → LengthFieldBasedFrameDecoder
 *      → ImMessageDecoder
 *      → HeartbeatHandler            ← 本类
 *      → ImServerHandler
 * </pre>
 * 业务消息会经过本类，先看是不是 PING/PONG：
 * <ul>
 *     <li>PING：回 PONG 后 <b>不</b> 向下传递（避免业务 handler 处理心跳）</li>
 *     <li>PONG：仅记录，不向下传递</li>
 *     <li>其它：ctx.fireChannelRead(msg) 继续往下走</li>
 * </ul>
 *
 * <h2>为什么用 {@link SimpleChannelInboundHandler}</h2>
 * 它自动 release 收到的 ByteBuf/POJO（与 ProtobufHandler 风格一致），
 * 减少内存泄漏风险。
 */
@ChannelHandler.Sharable
public class HeartbeatHandler extends SimpleChannelInboundHandler<ImMessage> {

    /** 单例（handler 无状态）。 */
    public static final HeartbeatHandler INSTANCE = new HeartbeatHandler();

    /** 心跳协议字符串（与 body 字段约定）。 */
    public static final String PING = "PING";
    public static final String PONG = "PONG";

    /** 私有构造：强制单例。 */
    private HeartbeatHandler() {}

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImMessage msg) {
        if (PING.equals(msg.getBody())) {
            // 收到 PING：回 PONG（不向下传递）
            System.out.println("[heartbeat] <- ping from " + ctx.channel().remoteAddress());
            ctx.writeAndFlush(new ImMessage(PONG));
            return;
        }
        if (PONG.equals(msg.getBody())) {
            // 收到 PONG：仅记录
            System.out.println("[heartbeat] <- pong from " + ctx.channel().remoteAddress());
            return;
        }
        // 非心跳：交给下游业务 handler
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // IdleStateHandler 在触发读/写空闲时抛出 IdleStateEvent
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                // 读空闲 N 秒：主动 PING 一次
                System.out.println("[heartbeat] read idle, sending PING to "
                        + ctx.channel().remoteAddress());
                ctx.writeAndFlush(new ImMessage(PING));
            } else if (event.state() == IdleState.WRITER_IDLE) {
                // 写空闲：服务端不主动 PING（避免无谓心跳）
            }
        } else {
            // 其它事件（channleActive/自定义事件）继续走默认处理
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[heartbeat] error: " + cause.getMessage());
        ctx.close();
    }
}
