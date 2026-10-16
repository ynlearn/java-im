package com.teachlearn.im.reactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 第 3 章教学辅助：用于测试 {@link SingleReactorServer} 的简易 NIO 客户端。
 *
 * <h2>本类的位置</h2>
 * 与 module-02-nio 的 NioEchoClient 几乎一致，但本类作为第 3 章 demo 的客户端使用，
 * 端口默认 {@link SingleReactorServer#DEFAULT_PORT}（9094）。
 *
 * <h2>注意</h2>
 * <ul>
 *     <li>本章是单线程阻塞模式（不走 Selector）—— 客户端通常不需要多路复用</li>
 *     <li>{@code NioEchoClient} 与本类最大差别仅在 default port 引用</li>
 *     <li>真实 IM 客户端（{@code ImClient}）会改用 Netty，因为还要处理重连、心跳</li>
 * </ul>
 */
public final class ReactorClient {

    /** 工具类不允许实例化。 */
    private ReactorClient() {}

    public static void main(String[] args) throws IOException {
        // 命令行格式：java ReactorClient [host] [port]
        // 不传 port 则连 SingleReactorServer.DEFAULT_PORT=9094
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : MasterSlaveReactorServer.DEFAULT_PORT;

        // try-with-resources 同时关 channel + scanner
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(host, port));
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("[nio-client] connected to " + host + ":" + port);
            // 256 字节读缓存（教学简化，不做"读多包"）
            ByteBuffer readBuf = ByteBuffer.allocate(256);

            // 客户端主循环：读 stdin → 写一行 → 读 echo
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();
                if ("bye".equalsIgnoreCase(line)) {
                    // 不主动发 "bye" 给服务端，避免服务端死循环
                    break;
                }
                // 写：String → byte[] → ByteBuffer.wrap → channel.write
                channel.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));

                // 读：clear 后等 read
                readBuf.clear();
                int n = channel.read(readBuf);
                if (n == -1) {
                    // 服务端关闭
                    System.out.println("[nio-client] server closed");
                    break;
                }
                // flip 切到读模式
                readBuf.flip();
                String echo = StandardCharsets.UTF_8.decode(readBuf).toString().trim();
                System.out.println("[nio-client] <- " + echo );
            }
        }
    }
}
