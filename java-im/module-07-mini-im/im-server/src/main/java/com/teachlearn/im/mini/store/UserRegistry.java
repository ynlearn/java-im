package com.teachlearn.im.mini.store;

import io.netty.channel.Channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 7 章：用户注册表。
 *
 * <h2>本类的核心目的</h2>
 * 维护 username → Channel 映射，是单聊路由和群聊广播的"调度中心"。
 *
 * <h2>核心方法</h2>
 * <ul>
 *     <li>{@link #register} —— 登录时建立映射；同名重连时返回旧 channel 用于"踢下线"</li>
 *     <li>{@link #unregister} —— 断线时根据 channel 反查 username 移除</li>
 *     <li>{@link #getChannel} —— 单聊路由"to" 字段</li>
 *     <li>{@link #snapshot} —— 群聊时遍历"所有在线用户"</li>
 * </ul>
 *
 * <h2>教学的"故意简化"</h2>
 * <ul>
 *     <li>单实例内存版（多实例需 Redis 共享 / 一致性 hash）</li>
 *     <li>不主动清理僵尸连接（生产用心跳 + 超时）</li>
 * </ul>
 */
public class UserRegistry {

    /** 内存版路由表：username → Channel。 */
    private final Map<String, Channel> users = new ConcurrentHashMap<>();

    /**
     * 注册用户。
     * <p>{@code ConcurrentHashMap.put} 是原子的；如果 username 已存在，
     * 会返回旧 channel（教学 demo 在这种情况下"踢掉"旧连接）。
     *
     * @return 旧 channel（如果有）；无旧值时返回 null
     */
    public Channel register(String username, Channel channel) {
        return users.put(username, channel);
    }

    /**
     * 注销：根据 channel 反查移除。
     * <p>用 {@code entrySet().removeIf(...)} 一次性遍历 + 删除，
     * 比先查 key 再 remove 少一次查询。
     */
    public void unregister(Channel channel) {
        users.entrySet().removeIf(e -> e.getValue() == channel);
    }

    public Channel getChannel(String username) {
        return users.get(username);
    }

    public boolean isOnline(String username) {
        Channel ch = users.get(username);
        return ch != null && ch.isActive();
    }

    public int onlineCount() {
        return users.size();
    }

    /**
     * 群聊用：返回在线用户的快照。
     * <p>返回的是<b>副本</b>（{@code new ConcurrentHashMap}），所以遍历过程中
     * 即使有 register/unregister 也不会抛 ConcurrentModificationException。
     * 代价是每次都复制一份小 map，群聊频率低时无关紧要。
     */
    public Map<String, Channel> snapshot() {
        return new ConcurrentHashMap<>(users);
    }
}
