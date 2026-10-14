package com.teachlearn.im.mini.handler;

import com.teachlearn.im.mini.protocol.ImCommand;
import com.teachlearn.im.mini.protocol.MessageType;
import com.teachlearn.im.mini.protocol.Protocol;
import com.teachlearn.im.mini.store.OfflineMessageStore;
import com.teachlearn.im.mini.store.UserRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 第 7 章：业务 handler（核心：登录/单聊/群聊/路由/离线回放）。
 *
 * <h2>本类的核心目的</h2>
 * mini IM 的"业务中枢"——所有消息类型（除 OFFLINE）都在这里被路由/转发。
 *
 * <h2>5 类消息的处理（重点记忆）</h2>
 * <ul>
 *     <li>{@code LOGIN} —— 注册用户、回放离线消息、广播上线</li>
 *     <li>{@code CHAT} —— 单聊：查 UserRegistry 找到目标 channel</li>
 *     <li>{@code GROUP_CHAT} —— 群聊：遍历所有在线 channel（除自己）</li>
 *     <li>{@code PING} —— 回 PONG</li>
 *     <li>其它（OFFLINE）—— 不在服务端处理（仅作为 LOGIN 时回放容器）</li>
 * </ul>
 *
 * <h2>AttributeKey：把 username 绑到 channel 上</h2>
 * <pre>
 *   ctx.channel().attr(USERNAME_KEY).set("alice");
 * </pre>
 * 作用：channelInactive 时能根据 channel 反查 username 注销。
 * 替代方案：在 UserRegistry 中维护反向 map（channel → username），但要双写一致性。
 * AttributeKey 是 Netty 推荐做法——一个 channel 一个 map，与 channel 生命周期绑定。
 */
@ChannelHandler.Sharable
public class MessageHandler extends SimpleChannelInboundHandler<ImCommand> {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    /**
     * channel 级别的 attribute key：保存"这个 channel 对应哪个 user"。
     * <p>{@link AttributeKey#valueOf(String)} 必须用全类共享的静态实例（必须是 valueOf 唯一）。
     */
    public static final AttributeKey<String> USERNAME_KEY = AttributeKey.valueOf("username");

    private final UserRegistry userRegistry;
    private final OfflineMessageStore offlineStore;

    public MessageHandler(UserRegistry userRegistry, OfflineMessageStore offlineStore) {
        this.userRegistry = userRegistry;
        this.offlineStore = offlineStore;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接刚建立，尚未登录（login 后才会写入 USERNAME_KEY）
        log.info("client connected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开：如果登录过，从注册表移除
        String username = ctx.channel().attr(USERNAME_KEY).get();
        if (username != null) {
            userRegistry.unregister(ctx.channel());
            broadcastSystem(username + " left, online=" + userRegistry.onlineCount());
            log.info("user {} disconnected, online={}", username, userRegistry.onlineCount());
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImCommand msg) {
        // 入口 switch：按 type 分发
        MessageType type = msg.getType();
        switch (type) {
            case LOGIN:
                handleLogin(ctx, msg);
                break;
            case CHAT:
                handleChat(ctx, msg);
                break;
            case GROUP_CHAT:
                handleGroupChat(ctx, msg);
                break;
            case PING:
                // 收到 PING 直接回 PONG
                ctx.writeAndFlush(new ImCommand(MessageType.PONG, "system", msg.getFrom(), ""));
                break;
            default:
                // OFFLINE 等其它类型不在服务端处理（OFFLINE 仅作为回放包装）
                log.warn("unhandled type: {}", type);
        }
    }

    /**
     * 登录流程（重点记忆，三件事）。
     * <ol>
     *     <li>注册到 UserRegistry（同名重连时踢掉旧连接）</li>
     *     <li>推离线消息（用 OFFLINE 包装保留原始时间戳）</li>
     *     <li>回 LOGIN_ACK + 广播上线</li>
     * </ol>
     */
    private void handleLogin(ChannelHandlerContext ctx, ImCommand msg) {
        String username = msg.getFrom();
        if (username.isEmpty()) {
            // 拒绝空用户名
            ctx.writeAndFlush(new ImCommand(MessageType.LOGIN_ACK, "system", "",
                    "FAIL: username empty"));
            return;
        }
        // 1) 注册（同名重连时 register() 返回旧 channel；close 旧 channel）
        Channel old = userRegistry.register(username, ctx.channel());
        if (old != null && old != ctx.channel()) {
            log.warn("user {} re-login from another connection, kicking old", username);
            old.close();
        }
        // 把 username 绑到 channel，便于 channelInactive 时反查
        ctx.channel().attr(USERNAME_KEY).set(username);

        // 2) 推离线消息
        List<ImCommand> pending = offlineStore.drain(username);
        for (ImCommand offline : pending) {
            // 用 OFFLINE 类型包装，保留原始时间戳
            ImCommand wrapped = new ImCommand(MessageType.OFFLINE, offline.getFrom(),
                    offline.getTo(), offline.getBody(), offline.getTimestamp());
            ctx.writeAndFlush(wrapped);
        }

        // 3) 回登录 ACK
        ctx.writeAndFlush(new ImCommand(MessageType.LOGIN_ACK, "system", username,
                "OK: offline=" + pending.size() + ", online=" + userRegistry.onlineCount()));

        // 4) 广播上线
        broadcastSystem(username + " joined, online=" + userRegistry.onlineCount());
        log.info("user {} logged in, online={}", username, userRegistry.onlineCount());
    }

    /**
     * 单聊：直转 / 离线存储 二选一。
     */
    private void handleChat(ChannelHandlerContext ctx, ImCommand msg) {
        String from = msg.getFrom();
        String to = msg.getTo();
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        Channel target = userRegistry.getChannel(to);
        if (target != null && target.isActive()) {
            // 目标在线：直接转
            target.writeAndFlush(msg);
        } else {
            // 目标离线：存到 OfflineMessageStore
            offlineStore.save(to, msg);
            // 告诉发送方"已暂存"
            ctx.writeAndFlush(new ImCommand(MessageType.SYSTEM, "system", from,
                    "user " + to + " is offline; saved"));
        }
    }

    /**
     * 群聊：遍历所有在线用户（除自己），在线直转、离线存。
     */
    private void handleGroupChat(ChannelHandlerContext ctx, ImCommand msg) {
        String from = msg.getFrom();
        if (from.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Channel> entry : userRegistry.snapshot().entrySet()) {
            String user = entry.getKey();
            Channel ch = entry.getValue();
            // 不回显给发送者
            if (user.equals(from)) {
                continue;
            }
            if (ch.isActive()) {
                ch.writeAndFlush(msg);
            } else {
                offlineStore.save(user, msg);
            }
        }
    }

    /**
     * 广播系统消息：遍历所有在线 channel。
     */
    private void broadcastSystem(String text) {
        ImCommand msg = new ImCommand(MessageType.SYSTEM, "system", Protocol.GROUP_TARGET, text);
        for (Channel ch : userRegistry.snapshot().values()) {
            if (ch.isActive()) {
                ch.writeAndFlush(msg);
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // IdleStateHandler 触发的事件
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                // 读空闲 30s：直接关闭（防止僵尸连接）
                log.info("read idle, closing {}", ctx.channel().remoteAddress());
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("error: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
