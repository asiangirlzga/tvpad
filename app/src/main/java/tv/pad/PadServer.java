package tv.pad;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny TCP server on the phone. Games on the TV connect to it (the phone is the hotspot, so it is the TV's gateway)
 * and receive one line per key event: "D96" = key 96 down, "U96" = key 96 up (Android key codes).
 */
public class PadServer {
    public static final int PORT = 8765;

    public interface Listener { void onClients(int count); }

    private final Set<Socket> clients = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Listener listener;
    private ServerSocket server;
    private volatile boolean running;
    public volatile String error;

    public PadServer(Listener l) { this.listener = l; }

    public void start() {
        running = true;
        pool.execute(new Runnable() {
            public void run() {
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new java.net.InetSocketAddress(PORT));
                } catch (IOException e) {
                    error = "Port " + PORT + " is busy. Close other apps and reopen.";
                    listener.onClients(-1);
                    return;
                }
                while (running) {
                    try {
                        final Socket s = server.accept();
                        s.setTcpNoDelay(true);
                        clients.add(s);
                        listener.onClients(clients.size());
                        pool.execute(new Runnable() { public void run() { watch(s); } });
                    } catch (IOException e) {
                        if (!running) return;
                    }
                }
            }
        });
    }

    /** Blocks until the TV disconnects, so we notice and forget it. */
    private void watch(Socket s) {
        try {
            InputStream in = s.getInputStream();
            while (in.read() >= 0) { /* ignore */ }
        } catch (IOException ignored) { }
        drop(s);
    }

    private void drop(Socket s) {
        if (clients.remove(s)) listener.onClients(clients.size());
        try { s.close(); } catch (IOException ignored) { }
    }

    public int count() { return clients.size(); }

    public void key(final int code, final boolean down) {
        final byte[] msg = ((down ? "D" : "U") + code + "\n").getBytes();
        sender.execute(new Runnable() {
            public void run() {
                for (Socket s : clients) {
                    try {
                        OutputStream o = s.getOutputStream();
                        o.write(msg);
                        o.flush();
                    } catch (IOException e) {
                        drop(s);
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Socket s : clients) { try { s.close(); } catch (IOException ignored) { } }
        clients.clear();
        sender.shutdownNow();
        pool.shutdownNow();
    }
}
