# Module 00 · 全景与基线 demo

## 本章目标

**用最简 Java Socket 写出一个 echo 服务**，作为后续所有章节的对照基线。

## 启动方式

### 1. 编译

```bash
cd java-im
mvn -pl module-00-overview -am clean package
```

### 2. 启动服务端

```bash
java -cp module-00-overview/target/classes \
     com.teachlearn.im.overview.SimpleSocketEcho 9090
```

预期输出：

```
[overview] echo server listening on port 9090
[overview] try: telnet 127.0.0.1 9090
```

### 3. 启动客户端（另开终端）

```bash
java -cp module-00-overview/target/classes \
     com.teachlearn.im.overview.SimpleSocketEchoClient 127.0.0.1 9090
```

预期输出：

```
[overview] connected to 127.0.0.1:9090
[overview] <- echo: hello im
[overview] <- echo: from baseline socket client
```

服务端输出：

```
[overview] client connected: /127.0.0.1:54321
[overview] recv: hello im
[overview] recv: from baseline socket client
[overview] client disconnected, server shutdown
```

## 关键代码讲解

- `ServerSocket.accept()`：阻塞直到有客户端连接。
- `BufferedReader.readLine()`：阻塞直到读到换行 / 流结束。
- `PrintWriter.println(x)` + autoflush：服务端回显到客户端。

## 局限与下一步

本章服务端一次只能服务一个客户端。要支持并发，必须：

- **第 1 章 BIO**：每连接起一个线程（或线程池）。
- **第 2 章 NIO**：单线程 + Selector 多路复用。

进入 [第 1 章：BIO 同步阻塞](../docs/01-bio.md)（待补充）。

## 常见坑

1. **忘记 `PrintWriter` autoflush**：客户端会等不到回显。解决办法：构造时传 `true`。
2. **服务端 `accept()` 循环只跑一次**：当前实现只接一个客户端就退出。第 1 章会修。
3. **资源未关闭**：必须用 try-with-resources，否则客户端断开后服务端会泄漏文件描述符。
