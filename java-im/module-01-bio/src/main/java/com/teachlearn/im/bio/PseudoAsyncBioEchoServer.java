package com.teachlearn.im.bio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第 1 章 demo：BIO 伪异步 echo 服务端（线程池版）。
 *
 * <h2>为什么叫"伪异步"</h2>
 * 用固定大小线程池处理客户端连接，<b>看起来</b>解决了 {@link BioEchoServer}
 * "线程数爆炸"的问题，但引入了新的隐患——
 *
 * <h2>两个新问题（教学重点）</h2>
 * <ul>
 *     <li><b>队列堆积</b>：线程池满（core + queue）后新连接被拒绝；
 *         本 demo 用 AbortPolicy 直接抛 {@code RejectedExecutionException}</li>
 *     <li><b>线程假死</b>：50 个线程都被 {@code readLine()} 阻塞 → 整个服务 IO 停摆，
 *         新连接 100% 失败，即使 CPU/内存"看起来"正常</li>
 * </ul>
 *
 * <h2>教学结论</h2>
 * 线程池救不了阻塞 IO——只要业务里有"读 socket 阻塞"，池大小就是"最多并发处理的请求数"。
 * 真正的解药是 NIO（单线程处理多连接）。
 */
public final class PseudoAsyncBioEchoServer {

    /** 默认端口，与 BioEchoServer 9091 区分开。 */
    public static final int DEFAULT_PORT = 9092;

    /** 工具类不允许实例化。 */
    private PseudoAsyncBioEchoServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /**
     * 主循环 + 线程池配置说明：
     *
     * <h2>线程池参数（建议理解每个数字的意图）</h2>
     * <ul>
     *     <li>core=50, max=50 —— 固定大小，不会伸缩（教学简化）</li>
     *     <li>queue=1000 —— 有界队列，防止无界堆积 OOM</li>
     *     <li>keepAlive=60s —— 没用（core==max 不会回收）</li>
     *     <li>AbortPolicy —— 满了直接抛异常，客户端必须做好重试</li>
     * </ul>
     */
    private static void start(int port) throws IOException {
        ExecutorService pool = new ThreadPoolExecutor(
                50,                                       // corePoolSize
                50,                                       // maximumPoolSize (固定大小)
                60, TimeUnit.SECONDS,                     // keepAliveTime
                new ArrayBlockingQueue<>(1000),           // workQueue
                r -> {
                    // ThreadFactory：定制线程名 + 守护标记
                    Thread t = new Thread(r, "pseudo-bio-worker");
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());    // 队列满 + max 满时直接抛异常

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[bio-pseudo] echo server listening on port " + port);
            System.out.println("[bio-pseudo] model: fixed thread pool (50 workers, 1000 queue)");

            // accepted 计数器仅用于给每个客户端打 id，便于日志追踪
            AtomicInteger accepted = new AtomicInteger();
            while (true) {
                Socket client = serverSocket.accept();
                int id = accepted.incrementAndGet();
                System.out.println("[bio-pseudo] client #" + id + " connected: "
                        + client.getRemoteSocketAddress());
                try {
                    // 关键路径：把 client 包装成 ClientHandler 提交给线程池
                    pool.execute(new ClientHandler(client, id));
                } catch (Exception e) {
                    // 队列满 + max 满 → AbortPolicy 抛 RejectedExecutionException
                    // 必须手动 close socket，否则 fd 泄漏
                    System.err.println("[bio-pseudo] reject client #" + id + ": " + e.getMessage());
                    client.close();
                }
            }
        }
    }

    /**
     * 与 BioEchoServer.ClientHandler 几乎一致，唯一差别是带 id 便于在多线程下区分日志。
     */
    private static final class ClientHandler implements Runnable {

        private final Socket client;
        private final int id;

        ClientHandler(Socket client, int id) {
            this.client = client;
            this.id = id;
        }

        @Override
        public void run() {
            try (Socket socket = client;
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                String line;
                while ((line = in.readLine()) != null) {                  // 阻塞在池里的线程上
                    if ("bye".equalsIgnoreCase(line)) {
                        out.println("bye");
                        break;
                    }
                    System.out.println("[" + Thread.currentThread().getName() + "] #" + id + " recv: " + line);
                    out.println("echo: " + line);
                }
            } catch (IOException e) {
                System.err.println("[" + Thread.currentThread().getName() + "] #" + id
                        + " io error: " + e.getMessage());
            }
        }
    }
}
