package com.teachlearn.im.mini.protocol;

/**
 * 第 7 章：协议常量。
 *
 * <h2>本类的核心目的</h2>
 * 集中存放 mini IM 自定义协议的所有"魔法值"和"约定"。
 * 把常量集中在一处的好处：
 * <ul>
 *     <li>新人接手时一处看清整个协议</li>
 *     <li>字段改名时改 1 处不漏</li>
 *     <li>代码阅读时看到常量名能直接联想到字段含义</li>
 * </ul>
 *
 * <h2>帧格式（重要）</h2>
 * 紧跟 Netty {@code LengthFieldBasedFrameDecoder} 之后，本应用层协议字段：
 * <pre>
 * +---------+---------+---------+---------+---------+---------+--------+
 * |  type   | fromLen |  from   |  toLen  |   to    | bodyLen |  body  |
 * |  1 B    |  1 B    |  N1 B   |  1 B    |  N2 B   |  4 B    |  N3 B  |
 * +---------+---------+---------+---------+---------+---------+--------+
 * </pre>
 * <p>注意：本协议不预置 magic 字段，单纯靠 length 切分。
 * 教学极简——生产务必加 magic 防止粘包错位。
 */
public final class Protocol {

    /**
     * 单帧最大字节数。
     * <p>超过这个长度的消息会抛 TooLongFrameException（由 LengthFieldBasedFrameDecoder 抛）。
     * 64KB 对聊天够用；如果是发图应该走单独文件服务。
     */
    public static final int MAX_FRAME_LENGTH = 64 * 1024;

    /**
     * LengthField 偏移 = 0：长度字段在帧的最前面。
     */
    public static final int LENGTH_FIELD_OFFSET = 0;

    /** 长度字段本身占 4B（int）。 */
    public static final int LENGTH_FIELD_LENGTH = 4;

    /**
     * 不需要 length 修正（实际帧总长度 = 4B length + payload）。
     * 与 ImMessageDecoder 用法不同：后者用 -8 是因为 magic 也算进 length。
     */
    public static final int LENGTH_ADJUSTMENT = 0;

    /**
     * 切完后不剥任何字节——下游 ImFrameDecoder 拿到的就是
     * [length(4) + type(1) + fromLen(1) + from + toLen(1) + to + bodyLen(4) + body]，
     * <b>但 ImFrameDecoder 当前按 type 起步读</b>，
     * 所以 mini IM 实际不使用 LengthFieldBasedFrameDecoder——而是在 ImFrameDecoder
     * 内部用 markReaderIndex/resetReaderIndex 自管理半包。
     */
    public static final int INITIAL_BYTES_TO_STRIP = 0;

    /**
     * 群发目标标记：{@code to = "*"} 表示"群发给所有人"。
     * <p>第 5 章 {@link Protocol#GROUP_TARGET} 的"约定俗成"，所有 handler 都要识别这个值。
     */
    public static final String GROUP_TARGET = "*";

    private Protocol() {}
}
