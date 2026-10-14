package com.teachlearn.im.mini.store;

import com.teachlearn.im.mini.protocol.ImCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 第 7 章：离线消息存储（教学极简版：内存版）。
 *
 * <h2>本类的核心目的</h2>
 * <ul>
 *     <li>用户离线时收到的消息暂存到本表</li>
 *     <li>用户登录时由 {@code MessageHandler.handleLogin} 调用 {@link #drain} 批量推回</li>
 * </ul>
 *
 * <h2>为什么 ConcurrentHashMap + List</h2>
 * <ul>
 *     <li>外层是 username → List，保证每个用户的离线消息独立</li>
 *     <li>用 {@code computeIfAbsent} 原子地"获取或创建" List，避免 if-null-then-put 的非原子</li>
 *     <li>内层 ArrayList 不是线程安全的，但因为每个用户 List 只被本用户消息路径访问（不同用户路径分摊），
 *         教学 demo 不再加锁；生产可换 CopyOnWriteArrayList 或加锁</li>
 * </ul>
 *
 * <h2>教学的"故意简化"</h2>
 * <ul>
 *     <li>无大小上限（生产应有 TTL / 容量上限）</li>
 *     <li>无持久化（重启即丢；生产用 Redis List / DB 存储）</li>
 *     <li>无重复消息检测（生产需幂等）</li>
 * </ul>
 */
public class OfflineMessageStore {

    /** 内存版"邮箱"：username → 待发消息列表。 */
    private final ConcurrentMap<String, List<ImCommand>> mailbox = new ConcurrentHashMap<>();

    /**
     * 存一条离线消息。
     * <p>{@code computeIfAbsent} 一次性完成"取 list or 建 list"，
     * 比 {@code if (!map.containsKey(k)) map.put(k, new ArrayList<>());} 更原子。
     */
    public void save(String username, ImCommand msg) {
        mailbox.computeIfAbsent(username, k -> new ArrayList<>()).add(msg);
    }

    /**
     * 拉取并清空该用户的离线消息（drain = "抽干"）。
     * <p>{@code mailbox.remove} 原子地把整个 list 取出，避免"边取边漏" 的竞态。
     *
     * @return 该用户当前待发消息列表（空 list 而不是 null，便于 for 循环）
     */
    public List<ImCommand> drain(String username) {
        List<ImCommand> list = mailbox.remove(username);
        return list == null ? java.util.Collections.emptyList() : list;
    }

    /** 统计所有用户待发消息总数（监控用）。 */
    public int totalPending() {
        return mailbox.values().stream().mapToInt(List::size).sum();
    }

    /** 统计每个用户待发数量（监控用）。 */
    public Map<String, Integer> stats() {
        Map<String, Integer> s = new ConcurrentHashMap<>();
        mailbox.forEach((k, v) -> s.put(k, v.size()));
        return s;
    }
}
