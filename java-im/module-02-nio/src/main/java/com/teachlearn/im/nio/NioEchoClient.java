package com.teachlearn.im.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 第 2 章 demo：NIO echo 客户端（单连接、单线程版）。
 *
 * <h2>与 BIO 客户端的 3 个核心差异（记忆点）</h2>
 * <ol>
 *     <li>用 {@link SocketChannel} 而不是 {@link java.net.Socket}：
 *         SocketChannel 既可同步阻塞也可非阻塞，本 demo 走阻塞模式（教学简化）。</li>
 *     <li>用 {@link ByteBuffer} 中转数据：所有读都要先 read 到 buffer，再 flip 才能解码。</li>
 *     <li>{@code channel.read(buf)} 返回 int：0=无数据，-1=对端关闭（FIN），>0=实际读取字节数。</li>
 * </ol>
 *
 * <h2>为什么本 demo 不注册 Selector</h2>
 * 客户端通常只需要 1 条连接，"连接数:线程数"也是 1:1，无需 Selector。
 * 真实客户端需要异步时，再加 Selector + 多 SocketChannel（参考 ReactorClient）。
 */
public final class NioEchoClient {

    /** 工具类不允许实例化。 */
    private NioEchoClient() {}

    public static void main(String[] args) throws IOException {
        // 命令行格式：java NioEchoClient [host] [port]
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : NioEchoServer.DEFAULT_PORT;

        // try-with-resources 同时关 channel 和 Scanner
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(host, port));
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("[nio-client] connected to " + host + ":" + port);
            // 复用一个 256 字节的读 buffer（教学简化）
            ByteBuffer readBuf = ByteBuffer.allocate(256);

            // 客户端主循环：读 stdin → 写 channel → 读 echo → 打印
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();
                if ("bye".equalsIgnoreCase(line)) {
                    break;
                }
                // 写：String → byte[] → ByteBuffer.wrap → channel.write
                // ByteBuffer.wrap 是把 byte[] "包"成一个 ByteBuf 视图（不复制）
                channel.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));

                // 读：每次循环 clear 一次，否则 buffer 满了会"读不到新数据"
                // 同步阻塞模式下，read() 会一直等到有数据才返回
                readBuf.clear();
                int n = channel.read(readBuf);
                if (n == -1) {
                    // 服务端关闭连接（FIN 到达）
                    System.out.println("[nio-client] server closed");
                    break;
                }
                // flip 切到读模式
                readBuf.flip();
                String echo = StandardCharsets.UTF_8.decode(readBuf).toString().trim();
                System.out.println("[nio-client] <- " + echo);
            }
        }
    }
}
