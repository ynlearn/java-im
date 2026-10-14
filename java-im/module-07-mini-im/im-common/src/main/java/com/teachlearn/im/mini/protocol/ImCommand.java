package com.teachlearn.im.mini.protocol;

import java.util.Objects;

/**
 * 第 7 章：IM 业务消息 POJO（贯穿项目核心数据结构）。
 *
 * <h2>本类的核心目的</h2>
 * mini IM 的"业务消息"在内存中就用本 POJO 表示：
 * <ul>
 *     <li>登录/单聊/群聊/系统通知/心跳/离线消息</li>
 *     <li>在 Netty Pipeline 中由 {@link com.teachlearn.im.mini.codec.ImFrameDecoder} 解析而来</li>
 *     <li>在写回时由 {@link com.teachlearn.im.mini.codec.ImFrameEncoder} 序列化为字节</li>
 * </ul>
 *
 * <h2>5 个字段（重点记忆）</h2>
 * <ul>
 *     <li>{@code type}：{@link MessageType}，决定消息怎么处理（路由/广播/回放）</li>
 *     <li>{@code from}：发送方用户名；系统消息为 "system"</li>
 *     <li>{@code to}：接收方；{@code "*"} 表示群发（见 {@link Protocol#GROUP_TARGET}）</li>
 *     <li>{@code body}：消息正文</li>
 *     <li>{@code timestamp}：发送时间（毫秒）</li>
 * </ul>
 *
 * <h2>为什么 POJO 不实现 Serializable</h2>
 * 本类的"序列化"走 {@link com.teachlearn.im.mini.codec.ImFrameEncoder} 写到 ByteBuf，
 * 不走 JDK Serializable。理由：
 * <ol>
 *     <li>JDK 序列化体积大、性能差</li>
 *     <li>自定义协议更可控（添加 magic、version 字段）</li>
 *     <li>Netty 的 ByteBuf 序列化/反序列化是 0 中间对象</li>
 * </ol>
 */
public class ImCommand {

    private final MessageType type;
    private final String from;
    private final String to;
    private final String body;
    private final long timestamp;

    /**
     * 简化的构造：自动用 {@code System.currentTimeMillis()} 作为 timestamp。
     * 服务端/客户端构造"发出去"的消息时用这个。
     */
    public ImCommand(MessageType type, String from, String to, String body) {
        this(type, from, to, body, System.currentTimeMillis());
    }

    /**
     * 完整构造：可显式指定 timestamp。
     * <p>用于"离线消息回放"——离线消息是之前某个时刻存的，
     * 重新发出去时应该保留原始时间戳，不能覆盖。
     */
    public ImCommand(MessageType type, String from, String to, String body, long timestamp) {
        this.type = Objects.requireNonNull(type);
        // null 安全：null 当空串
        this.from = from == null ? "" : from;
        this.to = to == null ? "" : to;
        this.body = body == null ? "" : body;
        this.timestamp = timestamp;
    }

    public MessageType getType() { return type; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getBody() { return body; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "ImCommand{type=" + type + ", from='" + from + "', to='" + to
                + "', body='" + body + "', ts=" + timestamp + '}';
    }
}
