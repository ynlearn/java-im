package com.teachlearn.im.mini.codec;

import com.teachlearn.im.mini.protocol.ImCommand;
import com.teachlearn.im.mini.protocol.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 第 7 章：消息解码器（自管半包版）。
 *
 * <h2>本类的核心目的</h2>
 * 把字节流解析为 {@link ImCommand} POJO。
 *
 * <h2>为什么不用 LengthFieldBasedFrameDecoder</h2>
 * mini IM 协议<b>不预置</b> length 字段（参见 {@link com.teachlearn.im.mini.protocol.Protocol} 注释）。
 * 在与 {@code ImMessageEncoder} 对接时，直接拼装 [type + fromLen + from + toLen + to + bodyLen + body]，
 * 所以本类必须自己管理"字节流分帧"。
 *
 * <h2>自管半包的标准技巧（必背）</h2>
 * <ol>
 *     <li>{@code markReaderIndex()} —— 在解析前留个书签</li>
 *     <li>读一个字段前先看 {@code readableBytes()} 够不够，不够就 {@code resetReaderIndex() + return}，
 *         让 Netty 下一批数据到了再试</li>
 *     <li>{@code ByteToMessageDecoder} 内部会持续累积 ByteBuf，所以"半包等下一批"是天然支持的</li>
 * </ol>
 *
 * <h2>第 5 章 ImMessageDecoder 与本类的差异</h2>
 * <ul>
 *     <li>第 5 章依赖 LengthFieldBasedFrameDecoder 切完半包，本类自己管</li>
 *     <li>第 5 章有 magic 校验（多一道错位防护），本类没有（教学极简）</li>
 * </ul>
 */
public class ImFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) {
            return;
        }
        // 留书签：任何字段不够都回退
        in.markReaderIndex();

        // 1) type
        byte typeCode = in.readByte();
        MessageType type;
        try {
            type = MessageType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            // 粘包错位：回退 1 字节，丢当前字节，下次再试
            // 教学 demo 没加 magic，这是唯一的"自愈"手段
            in.resetReaderIndex();
            in.skipBytes(1);
            return;
        }

        // 2) from（1B 长度 + N1 字节内容）
        if (in.readableBytes() < 1) {
            in.resetReaderIndex();
            return;
        }
        byte fromLen = in.readByte();
        if (in.readableBytes() < fromLen) {
            in.resetReaderIndex();
            return;
        }
        String from = in.readBytes(fromLen).toString(StandardCharsets.UTF_8);

        // 3) to（1B 长度 + N2 字节内容）
        if (in.readableBytes() < 1) {
            in.resetReaderIndex();
            return;
        }
        byte toLen = in.readByte();
        if (in.readableBytes() < toLen) {
            in.resetReaderIndex();
            return;
        }
        String to = in.readBytes(toLen).toString(StandardCharsets.UTF_8);

        // 4) body（4B 长度 + N3 字节内容）
        if (in.readableBytes() < 4) {
            in.resetReaderIndex();
            return;
        }
        int bodyLen = in.readInt();
        if (in.readableBytes() < bodyLen) {
            in.resetReaderIndex();
            return;
        }
        String body = in.readBytes(bodyLen).toString(StandardCharsets.UTF_8);

        out.add(new ImCommand(type, from, to, body));
    }
}
