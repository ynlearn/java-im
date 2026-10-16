# 第 5 章 · Netty 进阶

> 一句话目标：**掌握"半包/粘包 + 协议帧 + 心跳 + 重连 + 零拷贝"五个生产级能力**。

---

## 5.1 半包与粘包

TCP 是**字节流协议**，没有消息边界。两种典型问题：

| 现象 | 描述 | 原因 |
|---|---|---|
| **粘包** | 多个消息被一次 `read()` 收齐 | 发送方 Nagle、接收方 buffered |
| **半包** | 一个消息被拆成多次 `read()` 收齐 | MTU、socket buffer 不足 |

解决：消息头里带"长度字段"，接收方按长度解。

## 5.2 协议帧设计

本章采用极简版：

```
+--------+--------+--------+
| magic  | length |  body  |
|  4 B   |  4 B   |  N B   |
+--------+--------+--------+
```

- `magic = 0xCAFEBABE`：校验"是不是我自己的消息"
- `length`：body 字节数
- `body`：UTF-8 字符串（生产可换 Protobuf / JSON / FlatBuffers）

> 教学场景下不引入 CRC；生产环境务必加。

## 5.3 Netty 的拆包器：`LengthFieldBasedFrameDecoder`

`LengthFieldBasedFrameDecoder(maxFrameLen, lengthFieldOffset, lengthFieldLength, lengthAdjustment, initialBytesToStrip)`：

| 参数 | 含义 | 本章值 |
|---|---|---|
| `maxFrameLen` | 单帧最大字节 | 1024 |
| `lengthFieldOffset` | 长度字段偏移 | 4（magic 占 4B，length 紧跟其后） |
| `lengthFieldLength` | 长度字段字节数 | 4 |
| `lengthAdjustment` | 长度补偿（除 length 外的字节数） | 0（length 只算 body） |
| `initialBytesToStrip` | 剥掉前几个字节 | 0（保留 magic 给 decoder） |

## 5.4 心跳机制

### 5.4.1 `IdleStateHandler`

```java
ch.pipeline().addLast(new IdleStateHandler(10, 0, 0, TimeUnit.SECONDS));
//           读空闲阈值  写空闲阈值   全空闲阈值
```

- 服务端用 `READER_IDLE`：10 秒没收到数据，触发 `IdleStateEvent`。
- 客户端用 `WRITER_IDLE`：3 秒没发数据，主动发 PING 防止被服务端踢。

### 5.4.2 `userEventTriggered`

心跳 handler 通过该回调响应：

```java
@Override
public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
    if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
        ctx.writeAndFlush(new ImMessage("PING"));
    }
}
```

## 5.5 客户端重连：指数退避

```java
private void scheduleReconnect() {
    int delay = 1 << reconnectAttempt;  // 1, 2, 4, 8, 16 秒
    reconnectAttempt++;
    group.schedule(this::connect, delay, TimeUnit.SECONDS);
}
```

要点：
1. 复用同一个 `EventLoopGroup`，避免反复创建。
2. 退避上限 5 次（教学演示），生产应根据 SLO 调整。
3. 重连成功后必须把 `reconnectAttempt` 清零。

## 5.6 零拷贝（不演示代码，列要点）

Netty 的"零拷贝"主要在两个层面：

| 层面 | 手段 | 场景 |
|---|---|---|
| `CompositeByteBuf` | 把多个 ByteBuf 组合成一个视图，**不复制** | 多个 buffer 拼装成消息帧 |
| `FileRegion.transferTo` | 操作系统 `sendfile`，绕过用户态 | 大文件下载、零拷贝代理 |
| `ByteBuf.slice()` | 视图切片，**不复制** | 子区域引用 |

## 5.7 与生产 IM 的差距

本章 demo 是"教学级"实现。生产 IM 还需补：

- 用户认证、Token 校验
- 消息确认 ACK、重传
- 离线消息存储（Redis / DB）
- 集群、负载均衡、一致性
- 监控、限流、降级

→ [第 7 章 mini IM](../module-07-mini-im/) 会演示其中核心部分。

## 5.8 🔬 源码深读（可选）

- `LengthFieldBasedFrameDecoder#decode`：位运算拆长度字段。
- `IdleStateHandler#channelIdle`：写 `IdleStateEvent` 的内部时序。
- `PooledByteBufAllocator`：jemalloc 风格的内存池实现。

## 5.9 下一步

进入 [第 6 章：WebSocket](06-websocket.md)，看 Netty 如何把 HTTP 升级成全双工通道。
