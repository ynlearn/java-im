# Module 06 · WebSocket

## 本章目标

掌握 Netty WebSocket 的 pipeline 编排，让浏览器与 Java 服务建立全双工通道。

## 启动方式

### 服务端

```bash
mvn -pl module-06-websocket -am clean package

mvn -pl module-06-websocket exec:java \
    -Dexec.mainClass=com.teachlearn.im.websocket.WebSocketChatServer \
    -Dexec.args="9099"
```

### 网页客户端

打开 [src/main/resources/web/index.html](src/main/resources/web/index.html) 到 Chrome。

或本地起一个静态服务：

```bash
# 用 python 起静态服务（如已安装）
python -m http.server 8000 --directory src/main/resources/web
# 浏览器访问 http://localhost:8000/
```

> **注意**：浏览器 WebSocket 连接到 `ws://localhost:9099/ws`，确保服务端的端口就是 9099。

## 关键代码讲解

- `HttpServerCodec`：HTTP 字节 ↔ `HttpRequest/HttpContent`。
- `HttpObjectAggregator(8192)`：聚合分片 `HttpContent` → `FullHttpRequest`，WebSocket 握手需要完整请求。
- `WebSocketServerProtocolHandler("/ws")`：处理握手、Ping/Pong、Close。后续业务 handler 只看到 `TextWebSocketFrame`。
- `WebSocketChatServerHandler`：标注 `@Sharable`，维护在线 channel 集合。

## 验证多客户端广播

1. 启动服务端。
2. 在浏览器打开多个标签页 `http://localhost:8000/`。
3. 任一标签页发消息，其他标签页同步收到。

## 常见坑

1. **握手失败 404**：`WebSocketServerProtocolHandler` 路径不匹配（如客户端连 `/ws` 而 handler 路径是 `/chat`）。
2. **Cross-Origin 报错**：浏览器同源策略。开发环境用 `--disable-web-security` 启动 Chrome 或用 Nginx 反代。
3. **大消息丢失**：超过 `HttpObjectAggregator(8192)` 的消息会被丢弃，应调大或流式处理。
4. **Ping 没回 Pong**：`WebSocketServerProtocolHandler` 默认自动回 Pong，但如果自定义了 `WebSocketFrame` 处理会拦截，丢失自动回 Pong 逻辑。

## 练习题

1. 用 `wscat`（Node CLI）测试服务端：`npx wscat -c ws://localhost:9099/ws`。
2. 改造 handler：消息格式 `@user: 内容` 转发给指定 channel。
3. 给服务端加 JWT 鉴权：握手时校验 `Sec-WebSocket-Protocol` 头里的 token。

## 下一步

进入 [第 7 章：贯穿项目 mini IM](../docs/07-mini-im.md)——把 TCP + WebSocket + 自定义协议 + 路由 + 离线消息合并。
