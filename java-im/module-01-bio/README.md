# Module 01 · BIO 同步阻塞

## 本章目标

理解"一连接一线程"模型与"伪异步"的局限，引出 NIO 的必要性。

## 启动方式

### 经典 BIO 服务端（每连接一线程）

```bash
# 编译
mvn -pl module-01-bio -am clean package

# 启动服务端
java -cp module-01-bio/target/classes \
     com.teachlearn.im.bio.BioEchoServer 9091
```

### 伪异步 BIO 服务端（线程池）

```bash
java -cp module-01-bio/target/classes \
     com.teachlearn.im.bio.PseudoAsyncBioEchoServer 9092
```

### 客户端（支持并发）

```bash
# 单客户端
java -cp module-01-bio/target/classes \
     com.teachlearn.im.bio.BioEchoClient 127.0.0.1 9091

# 100 并发客户端（演示线程爆炸）
java -cp module-01-bio/target/classes \
     com.teachlearn.im.bio.BioEchoClient 127.0.0.1 9091 100

# 100 并发客户端打伪异步服务端（会触发拒绝）
java -cp module-01-bio/target/classes \
     com.teachlearn.im.bio.BioEchoClient 127.0.0.1 9092 100
```

## 关键代码讲解

- `BioEchoServer.start()`：`accept()` 主循环 + 每个客户端一个 `Thread`。
- `PseudoAsyncBioEchoServer`：50 线程 + 1000 队列的固定线程池。
- `BioEchoClient.runOnce()`：每个客户端发 3 行（其中 `bye` 触发服务端正常关闭）。

## 常见坑

1. **线程数爆炸**：在 `BioEchoServer` 上跑 1 万并发客户端，进程直接 OOM 崩溃。生产绝不能用。
2. **线程池满后异常**：`PseudoAsyncBioEchoServer` 用 `AbortPolicy`，队列满后 `RejectedExecutionException`。客户端必须做好重试。
3. **线程假死**：所有 worker 都被 `readLine()` 阻塞时，IO 完全停摆，但 CPU 与内存看起来"正常"。
4. **忘记关闭 Socket**：`ClientHandler.run()` 必须 try-with-resources，否则客户端断线后服务端泄漏 fd。

## 练习题

1. 把 `PseudoAsyncBioEchoServer` 的策略改成 `CallerRunsPolicy`，观察并发 100 时的现象。
2. 用 `jstack <pid>` 查看 100 并发时 `BioEchoServer` 的线程栈，理解每个线程都在等什么。
3. 思考：能否用 `Socket.setSoTimeout()` 让 worker 周期性地"醒"？与 NIO 的本质区别是什么？

## 下一步

进入 [第 2 章：NIO 多路复用](../docs/02-nio.md)。
