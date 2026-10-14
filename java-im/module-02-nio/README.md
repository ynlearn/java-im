# Module 02 · NIO 多路复用

## 本章目标

掌握 `Channel / Buffer / Selector` 三大件，学会用单线程 Selector 同时服务多个连接。

## 启动方式

### 服务端

```bash
mvn -pl module-02-nio -am clean package

java -cp module-02-nio/target/classes \
     com.teachlearn.im.nio.NioEchoServer 9093
```

预期：

```
[nio] echo server listening on port 9093
[nio] model: single-threaded Selector multiplexing
```

### 客户端

```bash
java -cp module-02-nio/target/classes \
     com.teachlearn.im.nio.NioEchoClient 127.0.0.1 9093
```

交互：

```
> hello
[nio-client] <- echo: hello
> world
[nio-client] <- echo: world
> bye
```

服务端日志：

```
[nio] client connected: /127.0.0.1:54321
[nio] recv: hello
[nio] recv: world
```

## 关键代码讲解

- `Selector.open()`：创建 epoll/kqueue 封装的多路复用器。
- `serverChannel.configureBlocking(false)`：非阻塞模式是注册到 Selector 的前提。
- `selector.select()`：阻塞直到至少一个 channel 有事件。
- `it.remove()`：**必须**手动移除已处理的 key，否则下次 select 会重复处理。
- `ByteBuffer.flip()`：切换为"读模式"才能从 buffer 读出数据。

## 常见坑

1. **忘记 `it.remove()`**：`selectedKeys()` 是已就绪事件的集合，不移除会重复处理导致事件风暴。
2. **`flip()` 时机错误**：`flip()` 前必须先 `write()`/`read()` 完成，否则读不到正确数据。
3. **`configureBlocking(false)` 没调**：注册 Selector 时报 `IllegalBlockingModeException`。
4. **业务逻辑阻塞 Selector 线程**：CPU 密集任务必须在业务线程池执行（第 4 章 Netty）。

## 练习题

1. 启动 100 个 `NioEchoClient`，观察服务端线程数（`jstack`）仍是 1 个。
2. 把 `ByteBuffer.allocate(256)` 改成 `allocateDirect(256)`，理解堆外内存与零拷贝的关系。
3. 修改服务端：注册 `OP_WRITE` 而不是同步 `write()`，避免大消息阻塞 Selector。

## 下一步

进入 [第 3 章：Reactor 模式](../docs/03-reactor.md)，看 NIO 如何被组织成主从 Reactor 架构。
