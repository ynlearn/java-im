# Module 03 · Reactor 模式

## 本章目标

理解三种 Reactor 模型（单 Reactor 单线程 / 单 Reactor 多线程 / 主从 Reactor），手写主从 Reactor，看清 Netty 模型的来历。

## 启动方式

### 单 Reactor 单线程（教学版）

```bash
mvn -pl module-03-reactor -am clean package

java -cp module-03-reactor/target/classes \
     com.teachlearn.im.reactor.SingleReactorServer 9094
```

### 主从 Reactor（Netty 模型预演）

```bash
java -cp module-03-reactor/target/classes \
     com.teachlearn.im.reactor.MasterSlaveReactorServer 9095
```

### 客户端：复用第 2 章的 NioEchoClient

```bash
java -cp module-02-nio/target/classes \
     com.teachlearn.im.nio.NioEchoClient 127.0.0.1 9095
```

## 关键代码讲解

- `SingleReactorServer`：1 个 Selector 同时处理 accept/read，业务在该线程同步执行。
- `MasterSlaveReactorServer`：
  - `mainReactor`（主线程）：只处理 OP_ACCEPT，把新连接 round-robin 分给 subReactor。
  - `subReactorGroup`（N 个线程）：每个独立跑事件循环，处理 OP_READ。
  - 关键：`selector.wakeup()` 在跨线程注册前必须调用，避免死锁。

## 验证主从 Reactor

```bash
# 在主从 Reactor 启动后，连接多次观察分配
for i in {1..5}; do
  java -cp module-02-nio/target/classes \
       com.teachlearn.im.nio.NioEchoClient 127.0.0.1 9095 &
done
```

日志会显示连接被分配到 `sub-reactor-0/1/2/...`。

## 常见坑

1. **跨线程 register 死锁**：在 mainReactor 线程调用 `subSelector.register()` 前必须 `wakeup()`。
2. **handler 与 channel 绑定**：handler 由 channel 的 `attachment` 携带，不要在外面缓存。
3. **业务阻塞**：单 Reactor 模型业务处理阻塞 = 全局 IO 阻塞，必须换成主从模型或加 worker 池。
4. **selectedKeys 不 remove**：`it.remove()` 是必须的，否则下次 select 会重复处理。

## 练习题

1. 修改 `SingleReactorServer`，在 `onRead()` 中 `Thread.sleep(2000)`，观察其他客户端能否正常 echo。
2. 给 `MasterSlaveReactorServer` 加 worker 池：`handler.onRead()` 只把消息丢给线程池处理。
3. 把 `EchoHandler` 改造成完整接口（`onAccept/onRead/onWrite/onClose`），用 `accept`/`read` 模式分发。

## 下一步

进入 [第 4 章：Netty 入门](../docs/04-netty-intro.md)——Netty 已经把 Reactor 模式封装成工业级 API。
