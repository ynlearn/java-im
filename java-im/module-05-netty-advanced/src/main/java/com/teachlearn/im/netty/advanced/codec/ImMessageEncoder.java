package com.teachlearn.im.netty.advanced.codec;

import com.teachlearn.im.netty.advanced.protocol.ImMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 第 5 章：消息编码器。
 *
 * <h2>本类的核心目的</h2>
 * 把 {@link ImMessage} POJO 写为字节流：[magic(4) + length(4) + body(N)]。
 * 与 {@link ImMessageDecoder} 是严格的"反义"关系。
 *
 * <h2>为什么用 {@link MessageToByteEncoder} 而不是手动写</h2>
 * <ul>
 *     <li>MessageToByteEncoder 自动管理 ByteBuf 分配（直接拿 ctx.alloc() 分配）</li>
 *     <li>零模板代码（不需要判断 msg 类型）</li>
 *     <li>Netty 内部还会在写完后自动 release ByteBuf</li>
 * </ul>
 */
public class ImMessageEncoder extends MessageToByteEncoder<ImMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ImMessage msg, ByteBuf out) {
        byte[] body = msg.bodyBytes();
        // 顺序：magic → length → body
        // ByteBuf.writeInt 默认大端序，与 ImMessage.fullFrame 保持一致
        out.writeInt(ImMessage.MAGIC);
        out.writeInt(body.length);
        out.writeBytes(body);
    }
}
