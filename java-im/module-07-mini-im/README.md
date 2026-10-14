# Module 07 · 贯穿项目 mini IM

## 本章目标

把 1-6 章能力合并，跑通单聊/群聊/离线消息完整流程。

## 启动方式

```bash
mvn -pl module-07-mini-im -am clean package

# 1) 启动服务端
mvn -pl module-07-mini-im/im-server exec:java \
    -Dexec.mainClass=com.teachlearn.im.mini.ImServer -Dexec.args="9100"

# 2) 启动客户端 A（终端 1）
mvn -pl module-07-mini-im/im-client exec:java \
    -Dexec.mainClass=com.teachlearn.im.mini.ImClient

# 3) 启动客户端 B（终端 2）
mvn -pl module-07-mini-im/im-client exec:java \
    -Dexec.mainClass=com.teachlearn.im.mini.ImClient
```

### 命令

| 输入 | 含义 |
|---|---|
| `login alice` | 以 alice 身份登录 |
| `@bob hi bob` | 给 bob 发单聊 |
| `@all hi everyone` | 群发 |
| `bye` | 退出 |

## 验证流程

1. 启动服务端。
2. 终端 1：`login alice` → `login alice` 成功。
3. 终端 2：`login bob` → 终端 1 收到 `[system] bob joined, online=2`。
4. 终端 1：`@bob hi` → 终端 2 收到 `[recv] ImCommand{type=CHAT, from=alice, to=bob, body=hi}`。
5. 终端 1 发：`@charlie hi` → 终端 1 收到 `[system] user charlie is offline; saved`。
6. 终端 3：`login charlie` → 看到 `OFFLINE` 消息 `from=alice, to=charlie, body=hi`。

## 关键文件

- `im-common/.../protocol/MessageType.java` —— 消息类型枚举
- `im-common/.../protocol/ImCommand.java` —— 业务消息 POJO
- `im-common/.../protocol/Protocol.java` —— 帧格式常量
- `im-common/.../codec/ImFrameDecoder.java` + `ImFrameEncoder.java` —— 协议编解码
- `im-server/.../ImServer.java` —— 启动
- `im-server/.../handler/MessageHandler.java` —— 业务核心
- `im-server/.../store/UserRegistry.java` —— 用户注册表
- `im-server/.../store/OfflineMessageStore.java` —— 离线消息
- `im-client/.../ImClient.java` —— 命令行客户端

## 常见坑

1. **没登录就发消息**：`@bob hi` 在未登录时直接被服务端丢弃，客户端会收到 `please login first` 提示。
2. **多端登录**：同一个 username 在第二个连接登录时，第一个连接会被服务端关闭。
3. **协议字节错位**：使用 `MessageType` 编码时务必 byte 对齐，错位会让所有后续字段错乱。

## 扩展练习

1. 加一个 `BIND` 类型消息：客户端发 `BIND{token=xxx}` 完成 JWT 鉴权。
2. 用 Redis 替换 `OfflineMessageStore`，观察重启服务后离线消息不丢。
3. 把 ImServer 拆为 mainReactor + subReactor，模拟 Netty 默认的 boss/worker 模型。
4. 把 ImClient 加上 ACK：收到消息后回 `ACK{msgId=...}`，服务端维护未确认队列。

## 教学旅程完成

到这里你已经走完"原理 → demo → 贯穿项目"的完整路径。回到 [README](../../README.md) 复习章节地图。
