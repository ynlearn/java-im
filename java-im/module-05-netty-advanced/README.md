# Module 05 · Netty 进阶

## 本章目标

掌握"协议帧 + 心跳 + 重连"三件套，做出可长时间运行的 IM 客户端/服务端。

## 启动方式

### 服务端

```bash
mvn -pl module-05-netty-advanced -am clean package

java -cp module-05-netty-advanced/target/classes:$(mvn -pl module-05-netty-advanced -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null) \
     com.teachlearn.im.netty.advanced.ImServer 9098
```

### 客户端（带心跳 + 自动重连）

```bash
java -cp module-05-netty-advanced/target/classes:$(同上) \
     com.teachlearn.im.netty.advanced.ImClient 127.0.0.1 9098
```

> 推荐使用 `mvn exec:java`，可自动处理 classpath：
> ```bash
> mvn -pl module-05-netty-advanced exec:java -Dexec.mainClass=com.teachlearn.im.netty.advanced.ImServer
> ```

## 关键代码讲解

- `ImMessage`：固定头 [magic(4) + length(4)] + 变长 body。
- `ImMessageDecoder` / `ImMessageEncoder`：自定义协议编解码。
- `LengthFieldBasedFrameDecoder`：解决半包/粘包（参数含义见 docs/05-netty-advanced.md §5.3）。
- `IdleStateHandler(10, 0, 0, SECONDS)`：服务端 10s 读空闲。
- `HeartbeatHandler.userEventTriggered`：响应空闲事件，发 PING/PONG。
- `ImClient.scheduleReconnect`：指数退避重连，1s/2s/4s/8s/16s。

## 验证心跳

1. 启动服务端。
2. 启动客户端，**不发送任何消息**。
3. 观察：
   - 客户端每 3 秒打印 `[heartbeat] write idle →`（不发，因为客户端不发 PING，但服务端的读空闲不会触发）。
   - **实际效果**：让客户端空闲 11s 以上，服务端会主动 PING；客户端需要回 PONG 才能保持连接。

## 验证重连

1. 启动客户端，连接成功。
2. 杀掉服务端。
3. 客户端日志：
   ```
   [im-client] reconnecting in 1s (attempt 1)
   [im-client] reconnecting in 2s (attempt 2)
   ...
   ```
4. 重新启动服务端，客户端自动重连。

## 常见坑

1. **`LengthFieldBasedFrameDecoder` 参数写错**：本章 `length` 字段只算 body 长度，所以 `lengthAdjustment = 0`（帧总长 = magic 4 + length 4 + body）。误写成 `-8` 会把帧长算成 body 长度，短消息直接抛 `CorruptedFrameException`，表现为"客户端发送请求无响应"。
2. **心跳 handler 不加 `@Sharable` 但又被多 channel 共享**：会抛 `IllegalStateException`。
3. **重连未清零计数器**：第 6 次重连时已重置 0 之后的退避失效。
4. **IdleStateHandler 不放在协议解码器之前**：会先解码失败再判空闲，错误顺序。

## 练习题

1. 把 `IdleStateHandler` 的超时改成 3s，观察客户端在 0 流量时是否被服务端 PING。
2. 把协议帧加上 CRC16 校验（magic 之前 / 之后），写解码器拒绝 CRC 错误帧。
3. 给 `ImClient` 加"心跳失败 N 次主动重连"（区别于连接断开重连）。

## 下一步

进入 [第 6 章：WebSocket](../docs/06-websocket.md)——浏览器与 Netty 服务的全双工通道。
