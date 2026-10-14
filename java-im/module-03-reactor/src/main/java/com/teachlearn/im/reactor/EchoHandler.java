package com.teachlearn.im.reactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * 第 3 章 Reactor 抽象：单个连接的事件处理器。
 *
 * <h2>本类的核心目的</h2>
 * 把"业务逻辑"从 Reactor 主循环里抽出来，让 Reactor 只做事件分发，
 * Handler 只做业务处理。这是 Reactor 模式最关键的<b>关注点分离</b>。
 *
 * <h2>为什么设计成 abstract</h2>
 * 真实的 Handler 会区分有状态/无状态、是否带协议等。教学 demo 把它设计成 abstract
 * 让调用方必须用匿名子类（如 {@code new EchoHandler(channel) {}}) 显式
 * "实例化"，避免误用。也方便后续章节（主从 Reactor、Netty）继承扩展。
 *
 * <h2>字段含义（注意 protected）</h2>
 * <ul>
 *     <li>{@link #channel} —— 当前处理器的目标 socket；Handler 与 channel 一对一</li>
 * </ul>
 * protected 允许子类在主从 Reactor demo 中访问。
 */
public abstract class EchoHandler {

    /** Handler 绑定的目标客户端 channel。 */
    protected final SocketChannel channel;

    protected EchoHandler(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * 业务核心：echo 一行字符串。
     *
     * <h2>简化点（教学不掩盖）</h2>
     * <ul>
     *     <li>每次 read 分配新 256B ByteBuffer，未做池化（参考 Netty PooledByteBufAllocator）</li>
     *     <li>未做半包处理：read 可能截断字符串</li>
     *     <li>write 是同步阻塞的：小消息够用，大消息必须用 OP_WRITE 异步写</li>
     * </ul>
     */
    public void onRead() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(256);
        int n = channel.read(buf);
        if (n == -1) {
            // 对端关闭（FIN 到达），我们也 close
            System.out.println("[reactor] client disconnected: " + channel.getRemoteAddress());
            channel.close();
            return;
        }
        if (n > 0) {
            // flip：写模式 → 读模式
            buf.flip();
            String line = StandardCharsets.UTF_8.decode(buf).toString().trim();
            // 打印当前线程名，便于观察"主从 Reactor 不同 sub 线程"的实际效果
            System.out.println("[reactor] " + Thread.currentThread().getName() + " recv: " + line);
            // 写回 echo
            channel.write(ByteBuffer.wrap(("echo: " + line + "\n").getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 关闭时清理资源。
     *
     * <p>本实现有个教学性质的小问题：
     * {@code channel.keyFor(channel.provider().openSelector())} 实际是
     * "临时开一个新 selector 查 key"，永远拿不到当前 key（拿到 null 就 no-op）。
     * 实际生产中会从外部传入 selector，或在 Handler 中缓存 key 引用。
     * 这里保留是因为单 Reactor demo 没出现调用方问题。</p>
     */
    public void close() throws IOException {
        SelectionKey key = channel.keyFor(channel.provider().openSelector());
        if (key != null) {
            key.cancel();
        }
        channel.close();
    }
}
