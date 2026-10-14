package com.teachlearn.im.overview;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * 第 0 章 demo：基线 Java Socket 客户端。
 *
 * <h2>与服务端代码的对照（学习要点）</h2>
 * <ul>
 *     <li>服务端用 {@link java.net.ServerSocket} 监听端口；客户端用 {@link Socket} 直接连远端</li>
 *     <li>两者在"已连接"之后完全对称：都有 {@code getInputStream/getOutputStream}</li>
 *     <li>这是后面所有章节的"最小公分母"——BIO/NIO/Netty 客户端都是从这演化的</li>
 * </ul>
 */
public final class SimpleSocketEchoClient {

    /**
     * 默认发送的几行演示数据。
     * 用 {@code "exit"} 作为约定的退出命令（因为 SimpleSocketEcho 协议是"按行读 + 空行退出"）。
     */
    private static final String[] DEFAULT_LINES = {
        "hello im",
        "from baseline socket client",
        "exit"
    };

    /** 工具类不允许实例化。 */
    private SimpleSocketEchoClient() {}

    public static void main(String[] args) throws IOException {
        // 命令行格式：java SimpleSocketEchoClient [host] [port]
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : SimpleSocketEcho.DEFAULT_PORT;

        // try-with-resources 同时关闭 Socket 和 Scanner
        // Socket 关闭 = 关闭 TCP 连接（四次挥手），Scanner 关闭对 System.in 无实际效果
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);   // autoflush=true
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))) {

            System.out.println("[overview] connected to " + host + ":" + port);

            // 简化协议：客户端发 3 行，约定"exit" 触发服务端按约定退出
            for (String line : DEFAULT_LINES) {
                if ("exit".equals(line)) {
                    // 客户端写一个空行 → 服务端 readLine() 拿到空串 → break
                    out.println("");                                                // 服务端读到的就是 ""
                    break;
                }
                // 写一行（末尾自动加 \n，因为 PrintWriter.println）
                out.println(line);
                // 同步读服务端的回显（一来一回）
                // 注意：这是最简单的"半双工"客户端，先发一行再等一行
                String echo = in.readLine();                                        // 阻塞等服务端写
                System.out.println("[overview] <- " + echo);
            }
        }
        // 离开 try 块时 socket.close() 被自动调用，四次挥手正常发生
    }
}
