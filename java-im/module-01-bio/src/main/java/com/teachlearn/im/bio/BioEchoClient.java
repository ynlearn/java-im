package com.teachlearn.im.bio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

/**
 * 第 1 章 demo：BIO echo 客户端。
 *
 * <h2>本类的核心目的</h2>
 * 用来测试 {@link BioEchoServer} 与 {@link PseudoAsyncBioEchoServer}。
 * 最大的特色是支持 <b>并发启动 N 个客户端</b>，便于演示：
 * <ul>
 *     <li>BIO 服务端会产生 N 个 worker 线程（jstack 可观察）</li>
 *     <li>伪异步服务端在 N &gt; 50 时会触发 RejectedExecutionException</li>
 * </ul>
 *
 * <h2>为什么用 CountDownLatch</h2>
 * 教学场景下，main 线程要"等所有并发客户端跑完再退出"——CountDownLatch 是 JDK 内置的线程同步工具，
 * 比手写 wait/notify 简单很多。N 个子线程各 countdown 一次，main 线程 await。
 */
public final class BioEchoClient {

    /** 工具类不允许实例化。 */
    private BioEchoClient() {}

    public static void main(String[] args) throws Exception {
        // 命令行格式：java BioEchoClient [host] [port] [concurrency]
        // concurrency 是并发客户端数，便于演示"线程爆炸"
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : BioEchoServer.DEFAULT_PORT;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        // N 个客户端都跑完后 main 退出
        CountDownLatch done = new CountDownLatch(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final int id = i;
            // 每个客户端起一个独立线程（不是 Netty 的 EventLoop，是真的 Thread）
            new Thread(() -> runOnce(host, port, id, done), "bio-client-" + id).start();
        }
        done.await();    // 阻塞直到所有客户端发完三行 + 收到回显
    }

    /**
     * 单个客户端的固定剧本：发 3 行，期望收 3 行。
     * <ol>
     *     <li>第 1 行：hello from client N</li>
     *     <li>第 2 行：second line N</li>
     *     <li>第 3 行：bye（约定结束）</li>
     * </ol>
     */
    private static void runOnce(String host, int port, int id, CountDownLatch done) {
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            String[] lines = {
                "hello from client " + id,
                "second line " + id,
                "bye"
            };
            for (String line : lines) {
                out.println(line);
                String echo = in.readLine();
                System.out.println("[bio-client-" + id + "] <- " + echo);
            }
        } catch (IOException e) {
            // 伪异步服务端在队列满时会拒绝：会到这里
            System.err.println("[bio-client-" + id + "] error: " + e.getMessage());
        } finally {
            // 无论成功失败都 countdown，让 main 退出
            done.countDown();
        }
    }
}
