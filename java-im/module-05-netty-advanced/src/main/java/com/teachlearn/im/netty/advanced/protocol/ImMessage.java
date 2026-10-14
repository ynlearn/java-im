package com.teachlearn.im.netty.advanced.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 第 5 章：自定义 IM 消息体（应用层协议核心）。
 *
 * <h2>本类的核心目的</h2>
 * 定义一个最简的应用层协议：每条消息 = <b>帧头（magic + length） + body</b>。
 * 教学场景下 body 是 UTF-8 字符串，生产可换 Protobuf / FlatBuffers / Thrift。
 *
 * <h2>协议帧格式（建议背诵）</h2>
 * <pre>
 * +--------+--------+--------+--------+
 * | magic  | length |  body  | (CRC)  |
 * |  4 B   |  4 B   |  N B   |  0 B   |
 * +--------+--------+--------+--------+
 * </pre>
 * <ul>
 *     <li><b>magic (4B)</b> —— 0xCAFEBABE，校验"是不是我的协议"</li>
 *     <li><b>length (4B)</b> —— body 字节数（不含帧头）</li>
 *     <li><b>body (NB)</b> —— 实际负载</li>
 *     <li>教学 demo 不加 CRC（生产应加 CRC32 / MD5 校验帧完整性）</li>
 * </ul>
 *
 * <h2>与 LengthFieldBasedFrameDecoder 配套</h2>
 * 在 Pipeline 中，{@code LengthFieldBasedFrameDecoder} 会按 length 字段切分半包，
 * 切完后剩下 magic + body，本类负责解析。
 * 参数 {@code (1024, 0, 4, -8, 0)} 的含义：
 * <ul>
 *     <li>1024 = maxFrameLength 单帧最大字节数</li>
 *     <li>0 = lengthFieldOffset 长度字段偏移（紧跟 magic 4B 后）</li>
 *     <li>4 = lengthFieldLength 长度字段本身 4B</li>
 *     <li>-8 = lengthAdjustment 长度字段"算到 body 结束"的修正（减去 magic 4B + length 4B）</li>
 *     <li>0 = initialBytesToStrip 切完后要剥掉几字节（0=不剥）</li>
 * </ul>
 */
public class ImMessage {

    /**
     * 魔数 0xCAFEBABE。
     * <p>作用：
     * <ul>
     *     <li>客户端校验服务端回应是不是自己的协议（防攻击）</li>
     *     <li>解析时 magic 错位则视为粘包残留（参见 ImMessageDecoder）</li>
     * </ul>
     * 选一个 4 字节"不会出现在普通字符串里"的值。
     */
    public static final int MAGIC = 0xCAFEBABE;

    /**
     * 帧头长度：magic 4B + length 4B = 8B。
     * <p>写所有"按帧长计算 buffer 大小"的地方都要用这个常量，避免魔数。
     */
    public static final int HEADER_LEN = 8;

    /** 消息体字符串。 */
    private final String body;

    public ImMessage(String body) {
        this.body = body;
    }

    public String getBody() {
        return body;
    }

    @Override
    public String toString() {
        return "ImMessage{body='" + body + "'}";
    }

    /**
     * 把 body 字符串编码为 UTF-8 字节数组。
     * 配套 {@link #decodeBody(byte[])} 使用。
     */
    public byte[] bodyBytes() {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 把 body 字节数组解码为字符串。
     */
    public static String decodeBody(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 拼装完整帧（用于单元测试、断点调试）。
     * <p>实际生产中 <b>不要</b> 用这个方法做网络传输——会多一次内存拷贝；
     * 真实路径是 {@link com.teachlearn.im.netty.advanced.codec.ImMessageEncoder} 直接写 ByteBuf。
     *
     * <h2>位运算写 magic/length 的解释</h2>
     * Java int 是大端序，ByteBuf.writeInt 默认也是大端序。
     * 写 byte 数组时每个字节要按大端序展开：
     * <ul>
     *     <li>magic &gt;&gt;&gt; 24 → 最高字节</li>
     *     <li>magic &gt;&gt;&gt; 16 → 次高字节</li>
     *     <li>magic &gt;&gt;&gt; 8  → 次低字节</li>
     *     <li>magic &amp; 0xFF → 最低字节</li>
     * </ul>
     */
    public byte[] fullFrame() {
        byte[] body = bodyBytes();
        int len = body.length;
        byte[] frame = new byte[HEADER_LEN + len];
        // magic 大端写入
        frame[0] = (byte) (MAGIC >>> 24);
        frame[1] = (byte) (MAGIC >>> 16);
        frame[2] = (byte) (MAGIC >>> 8);
        frame[3] = (byte) MAGIC;
        // length 大端写入
        frame[4] = (byte) (len >>> 24);
        frame[5] = (byte) (len >>> 16);
        frame[6] = (byte) (len >>> 8);
        frame[7] = (byte) len;
        // body 复制到 frame 末尾
        System.arraycopy(body, 0, frame, HEADER_LEN, len);
        return frame;
    }

    /**
     * 把一帧字节解析为 ImMessage（用于测试 / 替代 Decoder）。
     *
     * @param frame 完整帧（不裁切）
     * @throws IllegalArgumentException 帧长度不够、magic 错、length 不匹配
     */
    public static ImMessage parseFrame(byte[] frame) {
        if (frame == null || frame.length < HEADER_LEN) {
            throw new IllegalArgumentException("frame too short");
        }
        // 解析 magic（大端）
        int magic = ((frame[0] & 0xFF) << 24)
                | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8)
                | (frame[3] & 0xFF);
        if (magic != MAGIC) {
            throw new IllegalArgumentException("bad magic: 0x" + Integer.toHexString(magic));
        }
        // 解析 length
        int len = ((frame[4] & 0xFF) << 24)
                | ((frame[5] & 0xFF) << 16)
                | ((frame[6] & 0xFF) << 8)
                | (frame[7] & 0xFF);
        if (frame.length != HEADER_LEN + len) {
            throw new IllegalArgumentException(
                    "frame length mismatch: declared=" + len + ", actual=" + (frame.length - HEADER_LEN));
        }
        // 截取 body 部分
        byte[] body = Arrays.copyOfRange(frame, HEADER_LEN, frame.length);
        return new ImMessage(decodeBody(body));
    }
}
