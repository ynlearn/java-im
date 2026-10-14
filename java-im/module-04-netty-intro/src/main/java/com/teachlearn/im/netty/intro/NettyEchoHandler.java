package com.teachlearn.im.netty.intro;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;

/**
 * 第 4 章：Netty echo handler（业务处理器）。
 *
 * <h2>本类的核心目的</h2>
 * 继承 {@link ChannelInboundHandlerAdapter} 只需重写关心的生命周期回调，
 * Netty 负责把它们调度到对应的 EventLoop 线程。
 *
 * <h2>4 个常用回调方法（必背）</h2>
 * <ul>
 *     <li>{@link #channelActive} —— 连接刚建立（auth 时机）</li>
 *     <li>{@link #channelRead} —— 收到一帧数据（核心业务）</li>
 *     <li>{@link #channelReadComplete} —— 当前读批次完成（可在此 flush 累积的写）</li>
 *     <li>{@link #exceptionCaught} —— 异常捕获（必须 close channel，否则泄漏）</li>
 * </ul>
 *
 * <h2>重要：为什么不需要 @Sharable</h2>
 * 本 handler <b>不持有任何状态</b>（没有共享 map），所以可以给每个 channel 各自 new 一个。
 * 与 {@link NettyChatServerHandler} 不同：那里要共享"在线 channel 集合"，所以必须 @Sharable。
 *
 * <h2>注意：Netty 中"读"和"写"都是 {@link ByteBuf}</h2>
 * 不要用 String 直接发，要先 {@code Unpooled.copiedBuffer}。
 * ByteBuf 比 byte[] 强在：读写 index 分离、零拷贝、池化。
 */
public class NettyEchoHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接刚建好（TCP 三次握手完成），auth / 初始化的好时机
        System.out.println("[netty-echo] " + ctx.channel().remoteAddress() + " connected");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // msg 实际是 ByteBuf；Pipeline 上游的 Framer 决定了真实类型
        ByteBuf in = (ByteBuf) msg;
        // echo 前面加 "echo: " 前缀
        // 注意：prefix 单独分配一个 ByteBuf，再 wrappedBuffer 合并；这样原 in 的 readerIndex 不会被破坏
        ByteBuf prefix = Unpooled.copiedBuffer("echo: ", CharsetUtil.UTF_8);
        // wrappedBuffer 产生一个"组合 ByteBuf"，内部不复制但提供统一接口
        ByteBuf out = Unpooled.wrappedBuffer(prefix, in);
        // writeAndFlush = write + flush 一次完成；推荐用法
        ctx.writeAndFlush(out);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        // 一批读完成时确保 flush 干净（如果用了 ctx.write 而没 writeAndFlush）
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 任何异常都要打印并 close channel；否则 Netty 会吞掉异常
        System.err.println("[netty-echo] error: " + cause.getMessage());
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开（FIN 收到），清理资源
        System.out.println("[netty-echo] " + ctx.channel().remoteAddress() + " disconnected");
    }
}
