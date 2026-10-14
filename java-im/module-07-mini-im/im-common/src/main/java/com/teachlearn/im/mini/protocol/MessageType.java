package com.teachlearn.im.mini.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * 第 7 章：mini IM 消息类型枚举。
 *
 * <h2>本类的核心目的</h2>
 * 决定一条 ImCommand 怎么被服务端/客户端处理：
 * <ul>
 *     <li>LOGIN / LOGIN_ACK —— 登录流程</li>
 *     <li>CHAT / GROUP_CHAT —— 单聊 / 群聊</li>
 *     <li>PING / PONG —— 应用层心跳</li>
 *     <li>SYSTEM —— 系统通知（加入/离开）</li>
 *     <li>OFFLINE —— 离线消息（登录后回放）</li>
 * </ul>
 *
 * <h2>为什么用 byte 编码（而不是直接用 enum）</h2>
 * 网络传输必须用 byte/int 这样的紧凑类型。
 * 一个 byte 占 1B，而 enum 序列化至少要 1B 序号 + 类型信息；
 * 用 byte 反而更简洁、性能更好。
 *
 * <h2>数字编号为什么不连续</h2>
 * 教学场景下保持小数字便于读协议；真实生产应保持连续（id 0=未定义）。
 */
public enum MessageType {

    LOGIN(1),        // 客户端 → 服务端：登录
    LOGIN_ACK(2),    // 服务端 → 客户端：登录响应（含 offline messages 数量）
    CHAT(3),         // 单聊
    GROUP_CHAT(4),   // 群聊（@all）
    PING(5),
    PONG(6),
    SYSTEM(7),       // 系统通知
    OFFLINE(8);      // 服务端 → 客户端：离线消息（登录后回放）

    /** 协议字节值。 */
    private final byte code;

    /**
     * 反向索引：byte → enum。
     * <p>用 {@link HashMap} 缓存，避免每次 {@link #fromCode} 都遍历 values()。
     * 简单、安全、教学友好。
     */
    private static final Map<Byte, MessageType> BY_CODE = new HashMap<>();

    static {
        for (MessageType t : values()) {
            BY_CODE.put(t.code, t);
        }
    }

    MessageType(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    /**
     * 把协议字节解析为 enum。
     * @throws IllegalArgumentException 收到未定义的字节（粘包错位或协议不同步）
     */
    public static MessageType fromCode(byte code) {
        MessageType t = BY_CODE.get(code);
        if (t == null) {
            throw new IllegalArgumentException("unknown message type: " + code);
        }
        return t;
    }
}
