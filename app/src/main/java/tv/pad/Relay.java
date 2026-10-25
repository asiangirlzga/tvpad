package tv.pad;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP + WebSocket relay with no dependencies.
 * Serves tv.html / controller.html, and forwards joystick messages from phones ("pads") to TVs.
 * Pure Java on purpose, so it can be tested off-device.
 */
public class Relay {
    public interface Assets { byte[] get(String name); }
    private interface TextHandler { void onText(String text); }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private final Assets assets;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Conn> tvs = Collections.newSetFromMap(new ConcurrentHashMap<Conn, Boolean>());
    private final Map<Integer, Conn> pads = new ConcurrentHashMap<Integer, Conn>();
    private ServerSocket server;
    private volatile boolean running;
    private int port;
    private int nextId = 1;

    public Relay(Assets assets) { this.assets = assets; }

    /** Binds the first free port from 8080 and starts serving. Returns the port. */
    public int start() throws InterruptedException {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int p = 8080; p < 8100 && server == null; p++) {
                    try {
                        ServerSocket s = new ServerSocket();
                        s.setReuseAddress(true);
                        s.bind(new InetSocketAddress(p));
                        server = s;
                        port = p;
                    } catch (IOException ignored) { }
                }
            }
        });
        t.start();
        t.join();
        if (server == null) throw new IllegalStateException("No free port between 8080 and 8099");
        running = true;
        pool.execute(new Runnable() { public void run() { acceptLoop(); } });
        return port;
    }

    public int getPort() { return port; }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Conn c : tvs) c.close();
        for (Conn c : pads.values()) c.close();
        pool.shutdownNow();
    }

    /** Wi-Fi / hotspot IPv4 addresses of this device. */
    public static List<String> lanIps() {
        List<String> preferred = new ArrayList<String>();
        List<String> other = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase(Locale.ROOT);
                // skip mobile-data and tunnel interfaces; keep Wi-Fi, hotspot (ap0, swlan0, softap0, wlan1...), USB/ethernet
                boolean mobile = name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-")
                        || name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("ppp");
                if (mobile) continue;
                boolean wifiLike = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
                        || name.startsWith("softap") || name.startsWith("eth") || name.startsWith("rndis");
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (wifiLike) preferred.add(a.getHostAddress());
                    else if (a.isSiteLocalAddress()) other.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return preferred.isEmpty() ? other : preferred;
    }

    // ---------------------------------------------------------------- connections

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() { public void run() { handle(s); } });
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(10000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            String req = line(in);
            if (req == null) { s.close(); return; }
            String[] parts = req.split(" ");
            if (parts.length < 2) { s.close(); return; }
            Map<String, String> h = new HashMap<String, String>();
            String l;
            while ((l = line(in)) != null && l.length() > 0) {
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            String target = parts[1], path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            String key = h.get("sec-websocket-key");
            if (key != null && "websocket".equalsIgnoreCase(h.get("upgrade"))) {
                s.setSoTimeout(0);
                websocket(s, in, key, query);
            } else {
                http(s, path);
            }
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, n = 0;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (++n > 8192) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ---------------------------------------------------------------- HTTP

    private void http(Socket s, String path) throws IOException {
        String ctype = "text/html; charset=utf-8";
        byte[] body;
        if (path.equals("/info")) {
            StringBuilder sb = new StringBuilder("{\"tv\":[");
            List<String> ips = lanIps();
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("\"http://").append(ips.get(i)).append(':').append(port).append('"');
            }
            sb.append("]}");
            body = sb.toString().getBytes(UTF8);
            ctype = "application/json";
        } else {
            String name = path.equals("/") ? "tv.html" : path.equals("/c") ? "controller.html" : path.substring(1);
            body = (name.length() == 0 || name.contains("..") || name.contains("\\") || name.contains("/")) ? null : assets.get(name);
            if (name.endsWith(".json")) ctype = "application/json";
            else if (name.endsWith(".svg")) ctype = "image/svg+xml";
            else if (name.endsWith(".js")) ctype = "text/javascript";
        }
        OutputStream out = s.getOutputStream();
        if (body == null) {
            byte[] nf = "Not found".getBytes(UTF8);
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: " + nf.length + "\r\nConnection: close\r\n\r\n").getBytes(UTF8));
            out.write(nf);
        } else {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + ctype + "\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(UTF8));
            out.write(body);
        }
        out.flush();
        s.close();
    }

    // ---------------------------------------------------------------- WebSocket

    private void websocket(Socket s, InputStream in, String key, String query) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        String accept = b64(md.digest((key + GUID).getBytes(UTF8)));
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(UTF8));
        out.flush();
        final Conn c = new Conn(s);

        if (query.contains("role=tv")) {
            tvs.add(c);
            c.send(listJson());
            try {
                readLoop(c, in, new TextHandler() { public void onText(String t) { } });
            } catch (IOException ignored) {
            } finally {
                tvs.remove(c);
                c.close();
            }
        } else {
            final int id;
            synchronized (this) { id = nextId++; }
            pads.put(id, c);
            c.send("{\"t\":\"hello\",\"p\":" + id + "}");
            toTVs("{\"t\":\"join\",\"p\":" + id + "}");
            try {
                readLoop(c, in, new TextHandler() {
                    public void onText(String txt) {
                        String m = txt.trim();
                        if (m.length() >= 2 && m.length() < 400 && m.charAt(0) == '{' && m.charAt(m.length() - 1) == '}') {
                            String body = m.substring(0, m.length() - 1);
                            toTVs(body + (m.length() == 2 ? "" : ",") + "\"p\":" + id + "}");
                        }
                    }
                });
            } catch (IOException ignored) {
            } finally {
                pads.remove(id);
                toTVs("{\"t\":\"leave\",\"p\":" + id + "}");
                c.close();
            }
        }
    }

    private String listJson() {
        StringBuilder sb = new StringBuilder("{\"t\":\"list\",\"ps\":[");
        boolean first = true;
        for (Integer id : new TreeSet<Integer>(pads.keySet())) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        return sb.append("]}").toString();
    }

    private void toTVs(String json) {
        for (Conn t : tvs) t.send(json);
    }

    private static void readLoop(Conn c, InputStream in, TextHandler h) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return;
            int b1 = rd(in);
            int op = b0 & 15;
            boolean masked = (b1 & 128) != 0;
            long len = b1 & 127;
            if (len == 126) len = (rd(in) << 8) | rd(in);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | rd(in); }
            if (len > 65536) return;
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask);
            byte[] p = new byte[(int) len];
            readFully(in, p);
            if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            if (op == 8) return;
            if (op == 9) c.pong();
            else if (op == 1) h.onText(new String(p, UTF8));
        }
    }

    private static int rd(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = ((d[i] & 255) << 16) | (i + 1 < d.length ? (d[i + 1] & 255) << 8 : 0) | (i + 2 < d.length ? (d[i + 2] & 255) : 0);
            sb.append(B64.charAt((b >> 18) & 63)).append(B64.charAt((b >> 12) & 63));
            sb.append(i + 1 < d.length ? B64.charAt((b >> 6) & 63) : '=');
            sb.append(i + 2 < d.length ? B64.charAt(b & 63) : '=');
        }
        return sb.toString();
    }

    private static class Conn {
        final Socket s;
        final OutputStream out;

        Conn(Socket s) throws IOException { this.s = s; this.out = s.getOutputStream(); }

        synchronized void send(String text) {
            try {
                byte[] p = text.getBytes(UTF8);
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write(0x81);
                if (p.length < 126) f.write(p.length);
                else if (p.length < 65536) { f.write(126); f.write(p.length >> 8); f.write(p.length & 255); }
                else { f.write(127); for (int i = 7; i >= 0; i--) f.write((int) (((long) p.length >> (8 * i)) & 255)); }
                f.write(p);
                out.write(f.toByteArray());
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        synchronized void pong() {
            try { out.write(new byte[]{(byte) 0x8a, 0}); out.flush(); } catch (IOException e) { close(); }
        }

        void close() { try { s.close(); } catch (IOException ignored) { } }
    }
}
