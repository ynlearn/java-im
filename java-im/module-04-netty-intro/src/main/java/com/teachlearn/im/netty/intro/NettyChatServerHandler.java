package com.teachlearn.im.netty.intro;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 4 章：群聊 handler（共享单例）。
 *
 * <h2>本类的核心目的</h2>
 * 维护一个"在线 channel 集合"：每次有客户端连接，加入 map；断开时移除。
 * 收到消息时遍历 map 把消息写入每个 channel（广播）。
 *
 * <h2>为什么用 {@link ChannelHandler.Sharable}</h2>
 * 关键：{@link NettyChatServer#HANDLER} 是单例，所有连接都用同一个 handler 实例。
 * Netty 严格检查：未加 @Sharable 的 handler 多次 addLast 会抛异常
 * （"is not a @Sharable handler"）。这是为了提示"这个 handler 不该共享"。
 *
 * <h2>集合为何用 {@link ConcurrentHashMap}</h2>
 * <ul>
 *     <li>channelActive / channelInactive / channelRead 都可能并发触发
 *         （不同连接可能在不同 EventLoop 线程上）</li>
 *     <li>{@link ConcurrentHashMap} 保证 put/remove/迭代都是线程安全的</li>
 *     <li>迭代期间 map 结构变化也不会抛 ConcurrentModificationException</li>
 * </ul>
 */
@ChannelHandler.Sharable
public class NettyChatServerHandler extends ChannelInboundHandlerAdapter {

    /**
     * 在线 channel 集合。
     * <p>key 是 {@link Channel}（每个连接唯一），value 是昵称（本 demo 用 remoteAddress）。
     * 用 ConcurrentHashMap 是因为不同 EventLoop 线程会并发读写。
     */
    private final Map<Channel, String> online = new ConcurrentHashMap<>();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        // 新连接加入集合；用 remoteAddress 当昵称（教学简化）
        online.put(ch, ch.remoteAddress().toString());
        // 广播：通知所有人
        broadcast("[system] " + ch.remoteAddress() + " joined, online=" + online.size());
        System.out.println("[netty-chat] online=" + online.size());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 关键：ByteBuf 是引用计数的，必须 release，否则内存泄漏
        try {
            ByteBuf in = (ByteBuf) msg;
            // 把 ByteBuf 里的字节按 UTF-8 解码成 String
            String text = in.toString(StandardCharsets.UTF_8).trim();
            // 广播消息：附上发送者地址
            broadcast("[" + ctx.channel().remoteAddress() + "] " + text);
        } finally {
            // ReferenceCountUtil.release 把 ByteBuf 的 refCnt 减 1，归 0 时回收内存
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        // 客户端断开（FIN 到达），从集合移除
        online.remove(ch);
        broadcast("[system] " + ch.remoteAddress() + " left, online=" + online.size());
        System.out.println("[netty-chat] online=" + online.size());
    }

    /**
     * 广播：遍历在线 channel 写同一份消息。
     *
     * <h2>为什么不复用同一个 buf + duplicate</h2>
     * {@link ByteBuf#duplicate()} 返回的派生 buf 在 release 时会委托给底层 buf
     * （{@code AbstractDerivedByteBuf.release0 → unwrap().release()}），所以 dup 与
     * 原始 buf <b>共享 refCnt</b>。若用 {@code buf.duplicate() + ch.writeAndFlush(dup)}，
     * outbound buffer 写完 socket 后 release dup 也会让原始 buf 的 refCnt 归 0，
     * 后续任何 release（其它 channel 的 dup 或循环外的 {@code buf.release()}）都会抛
     * {@link io.netty.util.IllegalReferenceCountException}。
     * <p>最稳妥的写法是每个 channel 各自分配一份独立 buf。
     */
    private void broadcast(String text) {
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        for (Channel ch : online.keySet()) {
            // 每条 channel 自己的 ByteBuf：避免 duplicate 共享 refCnt 的陷阱
            ch.writeAndFlush(Unpooled.copiedBuffer(bytes));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[netty-chat] error: " + cause.getMessage());
        ctx.close();
    }
}
