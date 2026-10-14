package com.teachlearn.im.overview;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 第 0 章 demo：基线 Java Socket Echo。
 *
 * <h2>本类的核心目的</h2>
 * 整个教学体系的"起点对照物"——不依赖任何 IO 框架，只用 JDK 自带的
 * {@link ServerSocket} / {@link Socket} 演示最朴素的 C/S 通讯模型：
 * <b>accept → read → write → close</b>。
 *
 * <p>后续章节将逐步把它替换为：
 * <ol>
 *     <li>BIO（多线程）→ 解决并发 1 个客户端的瓶颈</li>
 *     <li>NIO（多路复用）→ 解决一连接一线程的资源浪费</li>
 *     <li>Reactor → 抽象出"事件驱动"模式</li>
 *     <li>Netty → 工程化封装</li>
 *     <li>WebSocket → HTTP 升级为全双工通道</li>
 * </ol>
 *
 * <h2>三个最关键的 JDK 类（记忆点）</h2>
 * <ul>
 *     <li>{@link ServerSocket} —— 服务端"监听 socket"，绑定端口，调用 {@code accept()} 等连接</li>
 *     <li>{@link Socket} —— 客户端/服务端"已连接 socket"，对应 TCP 连接</li>
 *     <li>输入/输出流 —— 在 Socket 上拿 {@code getInputStream()} / {@code getOutputStream()}</li>
 * </ul>
 */
public final class SimpleSocketEcho {

    /** 默认监听端口，避免与系统保留端口冲突。 */
    public static final int DEFAULT_PORT = 9090;

    /** 工具类不允许实例化。 */
    private SimpleSocketEcho() {}

    public static void main(String[] args) throws IOException {
        // 命令行格式：java SimpleSocketEcho [port]
        // 不传则用 DEFAULT_PORT=9090
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        startServer(port);
    }

    /**
     * 单连接阻塞式 echo 服务端。
     *
     * <h2>本方法的 4 个关键步骤（建议背诵）</h2>
     * <ol>
     *     <li>{@code new ServerSocket(port)} —— 创建监听 socket 并绑定端口</li>
     *     <li>{@code serverSocket.accept()} —— 阻塞等待客户端连接（操作系统内核完成三次握手后返回）</li>
     *     <li>拿到 {@link Socket} 后，通过 {@code getInputStream/getOutputStream} 收发数据</li>
     *     <li>try-with-resources 关闭 socket（释放 fd）</li>
     * </ol>
     *
     * <h2>本章的两个"故意缺陷"（教学意义）</h2>
     * <ul>
     *     <li><b>只能服务 1 个客户端</b>：accept 只调用 1 次就退出了，所以只能接 1 个客户端</li>
     *     <li><b>阻塞到底</b>：readLine() 会一直阻塞等客户端发数据，期间整个程序挂起</li>
     * </ul>
     * 这两个缺陷正是 BIO / NIO / Reactor 章节要解决的问题。
     */
    private static void startServer(int port) throws IOException {
        // try-with-resources：ServerSocket 实现了 AutoCloseable，try 块结束自动 close()
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[overview] echo server listening on port " + port);
            System.out.println("[overview] try: telnet 127.0.0.1 " + port);

            // 只演示一次连接：接收到一个客户端后退出主循环（教学简化）
            // 第 1 章 BIO 会把这里改成 while(true) 持续 accept
            try (Socket client = serverSocket.accept();                              // ① 阻塞等待客户端连接
                 BufferedReader in = new BufferedReader(                            // ② 用 BufferedReader 包装输入流
                         new InputStreamReader(client.getInputStream()));
                 PrintWriter out = new PrintWriter(client.getOutputStream(), true)) { // ③ autoflush=true 让 println 立即刷

                System.out.println("[overview] client connected: " + client.getRemoteSocketAddress());

                // 简化协议：按行读，遇到空行 / 流结束就退出
                String line;
                while ((line = in.readLine()) != null) {                            // ④ readLine() 也是阻塞的
                    if (line.isEmpty()) {
                        break;                                                      // 客户端主动发送空行表示结束
                    }
                    System.out.println("[overview] recv: " + line);
                    out.println("echo: " + line);                                   // 写回客户端（autoflush 立即发出）
                }
            }
            System.out.println("[overview] client disconnected, server shutdown");
        }
    }
}
