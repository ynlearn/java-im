package com.teachlearn.im.bio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第 1 章 demo：经典 BIO echo 服务端（每连接一线程）。
 *
 * <h2>本类的核心目的</h2>
 * 演示 BIO 同步阻塞模型：每个 {@code accept()} 返回的客户端连接都交给一个独立的
 * 工作线程处理，直到客户端断开才回收线程。
 *
 * <h2>三个关键缺陷（重点记忆）</h2>
 * <ul>
 *     <li><b>线程数爆炸</b>：并发连接数 = 线程数。1 万连接 ≈ 1 万线程，每个栈 1MB ≈ 10GB 内存</li>
 *     <li><b>C10K 问题</b>：C10K（"1 万并发连接"）场景下资源耗尽，进程崩溃</li>
 *     <li><b>线程上下文切换开销</b>：线程数越多，CPU 在线程切换上的浪费越严重</li>
 * </ul>
 *
 * <h2>改造路线</h2>
 * <ol>
 *     <li>{@link PseudoAsyncBioEchoServer}：用线程池限制最大线程数（仅缓解，不解决）</li>
 *     <li>第 2 章 NIO：单线程 + Selector 多路复用（根本解决）</li>
 * </ol>
 */
public final class BioEchoServer {

    /**
     * 默认端口。注意：与第 0 章基线 9090 区分开（教学上每个 demo 都用独立端口便于对比）。
     */
    public static final int DEFAULT_PORT = 9091;

    /**
     * 全局线程计数器（仅用于给 worker 线程起可读的名字）。
     * 用 {@link AtomicInteger} 是因为 main 线程自增，name 仅供日志区分，不必强一致。
     */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    /** 工具类不允许实例化。 */
    private BioEchoServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        start(port);
    }

    /**
     * 主循环：accept 客户端，每连接起一个新线程处理 echo。
     *
     * <h2>两个阻塞点（核心认知）</h2>
     * <ol>
     *     <li>{@code serverSocket.accept()} —— 没客户端时主线程挂起（OK，主线程本来就该等）</li>
     *     <li>{@code readLine()} —— 已连接的客户端不发数据时，<b>工作线程</b>挂起（真正的资源浪费）</li>
     * </ol>
     *
     * <h2>为什么这个 demo 不会 OOM 也能跑通</h2>
     * 本 demo 没有线程池上限——理论上无限线程会 OOM。
     * 实际上你会看到线程数线性增长，直到 OS 拒绝创建新线程（JVM 抛 {@code OutOfMemoryError: unable to create new native thread}）。
     * 用 jstack 可以看到 N 个 bio-worker 线程都在等 readLine。
     */
    private static void start(int port) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[bio] echo server listening on port " + port);
            System.out.println("[bio] model: one-connection-one-thread (no pool)");

            // 经典 BIO 主循环：持续 accept
            while (true) {
                // ① 主线程阻塞，等待新客户端连接
                Socket client = serverSocket.accept();
                System.out.println("[bio] client connected: " + client.getRemoteSocketAddress());

                // ② 每连接起一个新 worker 线程（缺陷放大点）
                // 命名为 "bio-worker-N" 便于 jstack / 日志追踪
                Thread worker = new Thread(new ClientHandler(client),
                        "bio-worker-" + THREAD_COUNTER.incrementAndGet());
                worker.setDaemon(false);   // 非 daemon：JVM 必须等它结束才能退出
                worker.start();
            }
        }
    }

    /**
     * 单个客户端连接的 echo 处理逻辑（inner class 隐藏细节）。
     *
     * <p>设计为 {@link Runnable} 是因为我们要把它交给 Thread 跑；
     * 持有 {@link Socket} 引用直到线程结束，整个连接生命周期都包在 try-with-resources 里。
     */
    private static final class ClientHandler implements Runnable {

        private final Socket client;

        ClientHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            // 双重 try-with-resources：socket + 输入流 + 输出流都自动关闭
            // 客户端断开（FIN）→ 流的 read 返回 -1 → while 退出 → 自动 close
            try (Socket socket = client;
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                // 简化协议：按行读，遇到 "bye" 正常关闭
                String line;
                while ((line = in.readLine()) != null) {                            // 阻塞 ②
                    if ("bye".equalsIgnoreCase(line)) {
                        out.println("bye");                                          // 回 "bye" 客户端收到后也会退出
                        break;
                    }
                    // 打印时附上线程名，便于观察"每连接一线程"的实际效果
                    System.out.println("[" + Thread.currentThread().getName() + "] recv: " + line);
                    out.println("echo: " + line);
                }
            } catch (IOException e) {
                // 客户端断线通常会抛 SocketException，是正常路径（不一定是错误）
                System.err.println("[" + Thread.currentThread().getName() + "] io error: " + e.getMessage());
            } finally {
                System.out.println("[" + Thread.currentThread().getName() + "] client disconnected");
            }
        }
    }
}
