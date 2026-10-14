# java-im · Java 网络通讯教学仓库

> 团队内训用 Java 网络通讯教学仓库。从原生 `java.net.Socket` 出发，递进到 BIO / NIO / Reactor / Netty / WebSocket，每章独立可讲、可跑。

## 学习路径

```
第 0 章 全景与基线  ──▶  第 1 章 BIO  ──▶  第 2 章 NIO  ──▶  第 3 章 Reactor
                                                                    │
                                                                    ▼
        第 7 章 贯穿项目 mini IM  ◀──  第 6 章 WebSocket  ◀──  第 5 章 Netty 进阶
                                            │
                                            └──  第 4 章 Netty 入门
```

| # | 章节 | 重点 | demo |
|---|---|---|---|
| 0 | 全景与基线 | C/S 模型、TCP 状态机、IO 模型对比 | `SimpleSocketEcho` |
| 1 | BIO 同步阻塞 | 一连接一线程、伪异步 | `bio-echo-server` |
| 2 | NIO 多路复用 | Channel / Buffer / Selector | `nio-echo-server` |
| 3 | Reactor 模式 | 单/多线程 Reactor、主从 Reactor | `reactor-echo` |
| 4 | Netty 入门 | EventLoop / Pipeline / Handler | `netty-echo` + `netty-chat` |
| 5 | Netty 进阶 | 拆包粘包、心跳/重连、内存池 | 自定义协议 IM 消息帧 |
| 6 | WebSocket | 握手、帧格式、ping/pong | `netty-websocket-chat` |
| 7 | mini IM | 综合实战 | 单聊/群聊/离线消息 |

每个章节在 `docs/0X-xxx.md` 有独立原理文档，章节 module 里有独立可运行 demo。

## 技术栈

- JDK 17
- Maven 3.8+（多模块）
- Netty 4.1.x
- SLF4J + Logback（日志）
- JUnit 5（测试）

## 快速开始

```bash
# 1. 编译整仓库
mvn -f java-im/pom.xml clean package

# 2. 跑第 0 章基线 demo
# 终端 A：
java -cp java-im/module-00-overview/target/classes \
     com.teachlearn.im.overview.SimpleSocketEcho 9090
# 终端 B：
java -cp java-im/module-00-overview/target/classes \
     com.teachlearn.im.overview.SimpleSocketEchoClient 127.0.0.1 9090
```

## 目录结构

```
java-im/
├── pom.xml                          # 父 POM（版本/依赖统一管理）
├── README.md                        # 本文件
├── docs/                            # 各章原理文档（Mermaid 图、源码引用）
│   ├── 00-overview.md
│   ├── 01-bio.md
│   └── ...
└── module-XX-*/                     # 每章独立 Maven 模块
    ├── pom.xml
    ├── src/main/java/...
    └── README.md                    # 运行步骤、讲解要点
```

## 章节详细设计

详见 [`docs/00-overview.md`](docs/00-overview.md) 与 [`java-im-plan` 计划文件](#)。
