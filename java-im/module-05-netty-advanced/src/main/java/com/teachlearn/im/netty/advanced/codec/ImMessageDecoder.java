package com.teachlearn.im.netty.advanced.codec;

import com.teachlearn.im.netty.advanced.protocol.ImMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 第 5 章：消息解码器。
 *
 * <h2>本类的核心目的</h2>
 * 配合上游的 {@code LengthFieldBasedFrameDecoder}，把"一帧原始字节"解析为
 * {@link ImMessage} POJO。
 *
 * <h2>Pipeline 顺序（重要）</h2>
 * <pre>
 * Bytes → LengthFieldBasedFrameDecoder（切半包）
 *      → ImMessageDecoder（解析 magic+body）   ← 本类
 *      → HeartbeatHandler
 *      → ImServerHandler / ImClientHandler
 * </pre>
 *
 * <h2>为什么不需要自己处理半包</h2>
 * {@link ByteToMessageDecoder} 内部累积 ByteBuf（refCnt 由它管理），
 * 一次 decode 只处理"已有数据能解析出多少完整消息"，所以本类只需要关注"完整帧"如何解析。
 *
 * <h2>错位 magic 的处理技巧</h2>
 * 在粘包边界错误时，前 4 字节可能不是 magic。本类用 {@code markReaderIndex} +
 * {@code skipBytes(1)} 向前推 1 字节，让下一轮 decode 再试。
 */
public class ImMessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 长度字段已由 LengthFieldBasedFrameDecoder 剥掉，这里只校验 magic + 读取 body
        if (in.readableBytes() < ImMessage.HEADER_LEN) {
            // 不足 8 字节，等待更多数据
            return;
        }
        // markReaderIndex：留个书签，万一 magic 错位可以回退
        in.markReaderIndex();
        int magic = in.readInt();
        if (magic != ImMessage.MAGIC) {
            // magic 错位：回退 1 字节，丢弃当前"错位字节"，下次再解
            // 这是处理"切错帧"时最经典的做法
            in.resetReaderIndex();
            in.skipBytes(1);
            return;
        }
        int len = in.readInt();
        if (in.readableBytes() < len) {
            // body 还没到：回退到书签，等下一批数据
            in.resetReaderIndex();
            return;
        }
        // 一切就绪，读 body
        byte[] body = new byte[len];
        in.readBytes(body);
        out.add(new ImMessage(ImMessage.decodeBody(body)));
    }
}
