package io.siggi.togethertunes.http;

import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP listener: a SiggiHTTP server whose connection handlers (and so every request handler)
 * run on virtual threads, with the {@link Router} answering all paths.
 *
 * <p>We own the {@link ServerSocket} and the accept loop (SiggiHTTP's own listener is deprecated
 * and uses platform threads); each accepted socket goes to {@link HTTPServer#handle(Socket)},
 * which runs it on the virtual-thread executor given to {@link HTTPServerBuilder#setExecutor}.
 */
public final class WebServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(WebServer.class.getName());
    private static final int BACKLOG = 256;

    private final HTTPServer server;
    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final Thread acceptor;
    private volatile boolean closed;

    private WebServer(HTTPServer server, ServerSocket serverSocket, ExecutorService executor) {
        this.server = server;
        this.serverSocket = serverSocket;
        this.executor = executor;
        this.acceptor = Thread.ofPlatform().name("http-accept-" + serverSocket.getLocalPort()).unstarted(this::acceptLoop);
    }

    /**
     * Binds {@code bind:port} (port 0 picks a free one) and starts accepting connections.
     *
     * @param tmpDir directory SiggiHTTP may use for temporary files (multipart parsing is off, so
     *               normally unused)
     */
    public static WebServer start(String bind, int port, Router router, File tmpDir) throws IOException {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-", 0).factory());
        HTTPServer server = new HTTPServerBuilder()
                .setExecutor(executor)
                .setTmpDir(tmpDir)
                .build();
        server.setServerName("TogetherTunes");
        // Request bodies are streamed by the handlers (Request.copyBody / bodyToFile), which apply
        // their own limits. SiggiHTTP's defaults would spool any multipart/form-data body to tmpDir
        // before routing (on every path, even unauthenticated) and refuse an
        // "Expect: 100-continue" body over 2 GB with a plain-text 413.
        server.setIgnoringMultipartFormData(true);
        server.setUploadLimit(0L);
        server.responderRegistry.register("/", router);
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(bind), port), BACKLOG);
        } catch (IOException | RuntimeException e) {
            socket.close();
            executor.shutdownNow();
            throw e;
        }
        WebServer webServer = new WebServer(server, socket, executor);
        webServer.acceptor.start();
        return webServer;
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(System.Logger.Level.WARNING, "accept() failed", e);
                }
                continue;
            }
            try {
                server.handle(socket);
            } catch (RuntimeException e) {
                // e.g. the executor was shut down concurrently with close()
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                if (!closed) {
                    LOG.log(System.Logger.Level.WARNING, "Could not hand off a connection", e);
                }
            }
        }
    }

    /** The bound port. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** The underlying SiggiHTTP server, e.g. to configure trusted proxies or limits. */
    public HTTPServer server() {
        return server;
    }

    /** Blocks until {@link #close()} has stopped the accept loop. */
    public void join() throws InterruptedException {
        acceptor.join();
    }

    /**
     * Stops accepting connections and interrupts the handlers of open ones (waiting up to 5 s for
     * them to finish).
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
        try {
            acceptor.join(5000);
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
