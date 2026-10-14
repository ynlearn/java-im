package com.teachlearn.im.mini.codec;

import com.teachlearn.im.mini.protocol.ImCommand;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

/**
 * 第 7 章：消息编码器。
 *
 * <h2>本类的核心目的</h2>
 * 把 {@link ImCommand} POJO 写为字节流：与 {@link ImFrameDecoder} 严格"反义"。
 *
 * <h2>写入格式（与 Protocol 注释一致）</h2>
 * <pre>
 * type(1B) + fromLen(1B) + from(N1B) + toLen(1B) + to(N2B) + bodyLen(4B) + body(N3B)
 * </pre>
 *
 * <h2>为什么不依赖 LengthFieldBasedFrameEncoder</h2>
 * mini IM 协议不预置 length 字段，{@link ImFrameDecoder} 也没消费 length 字段，
 * 所以本类直接写"业务字段"——Netty 会在写出时通过 {@code addLast(LengthFieldPrepender(4))} 补上 length 前缀。
 * （在 ImServer/ImClient 的 Pipeline 中目前没用 prepender，所以发送时直接写裸字段，靠接收端 ImFrameDecoder 自管半包）
 *
 * <h2>几个长度字段选 1B/4B 的取舍</h2>
 * <ul>
 *     <li>type 1B：消息类型 < 256 种</li>
 *     <li>from/to 1B：用户名 < 255 字符，1B 足够</li>
 *     <li>body 4B：消息体可能 64KB+（图片 base64 编码），需要 4B 长度</li>
 * </ul>
 */
public class ImFrameEncoder extends MessageToByteEncoder<ImCommand> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ImCommand msg, ByteBuf out) {
        // 把字符串先编码为 UTF-8 字节数组
        byte[] from = msg.getFrom().getBytes(StandardCharsets.UTF_8);
        byte[] to = msg.getTo().getBytes(StandardCharsets.UTF_8);
        byte[] body = msg.getBody().getBytes(StandardCharsets.UTF_8);

        // 按协议顺序写入 ByteBuf
        out.writeByte(msg.getType().code());   // type 1B
        out.writeByte(from.length);             // fromLen 1B
        out.writeBytes(from);                   // from N1B
        out.writeByte(to.length);               // toLen 1B
        out.writeBytes(to);                     // to N2B
        out.writeInt(body.length);              // bodyLen 4B
        out.writeBytes(body);                   // body N3B
    }
}
