# Module 04 · Netty 入门

## 本章目标

掌握 Netty 四大组件（EventLoop / Channel / Pipeline / Handler），跑通 echo 和群聊两个 demo。

## 启动方式

### Echo 服务端 + 客户端

```bash
mvn -pl module-04-netty-intro -am clean package

# 服务端
java -cp module-04-netty-intro/target/classes \
     com.teachlearn.im.netty.intro.NettyEchoServer 9096

# 客户端（另开终端）
java -cp module-04-netty-intro/target/classes \
     com.teachlearn.im.netty.intro.NettyEchoClient 127.0.0.1 9096
```

客户端交互：

```
> hello
[netty-echo-client] <- echo: hello
> bye
```

### 群聊服务端 + 多个客户端

```bash
# 服务端
java -cp module-04-netty-intro/target/classes \
     com.teachlearn.im.netty.intro.NettyChatServer 9097

# 客户端 A
java -cp module-04-netty-intro/target/classes \
     com.teachlearn.im.netty.intro.NettyChatClient 127.0.0.1 9097

# 客户端 B、C（更多终端）
java -cp module-04-netty-intro/target/classes \
     com.teachlearn.im.netty.intro.NettyChatClient 127.0.0.1 9097
```

任一客户端发消息，其他客户端都会收到广播。

## 关键代码讲解

- `NettyEchoServer`：标准 `ServerBootstrap` 引导器。
- `NettyEchoHandler`：继承 `ChannelInboundHandlerAdapter`，处理 `channelActive / channelRead / exceptionCaught`。
- `NettyChatServerHandler`：标注 `@ChannelHandler.Sharable`，维护在线 channel 集合。
- `broadcast()` 中 `buf.duplicate()` 是关键——避免把同一个 ByteBuf 引用计数给多个 channel 时混乱。

## 常见坑

1. **忘记 `@Sharable`**：群聊 handler 没加注解，Netty 启动时直接抛 `IllegalStateException`。
2. **ByteBuf 泄漏**：handler 接收 msg 后如果不向下传递，必须 `ReferenceCountUtil.release(msg)` 或调用 `ctx.fireChannelRead(msg)`。
3. **handler 跨 EventLoop 共享**：同一 handler 实例可能被多个 EventLoop 调用，必须线程安全（如 `ConcurrentHashMap`）。
4. **handler 里有阻塞操作**：业务处理会阻塞 EventLoop 线程，应放进 worker 线程组或 `EventLoop.execute()` 派发。

## 练习题

1. 把 `NettyChatServerHandler.broadcast()` 中的 `buf.duplicate()` 去掉，观察是否抛出引用计数异常。
2. 给 `NettyEchoHandler` 加一个 `IdleStateHandler`（读空闲 10s 关闭连接）。
3. 实现"私聊"：消息格式 `@user: 内容`，只发给指定 channel。

## 下一步

进入 [第 5 章：Netty 进阶](../docs/05-netty-advanced.md)——拆包粘包、心跳、重连。
