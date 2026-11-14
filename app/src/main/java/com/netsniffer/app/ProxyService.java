package com.netsniffer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * 核心代理监听服务
 * - 在指定端口启动 ServerSocket
 * - 接收来自指定IP的HTTP/HTTPS流量
 * - 记录请求元数据（URL、方法、头部、大小）
 * - 原样转发，不修改任何内容
 */
public class ProxyService extends Service {

    private static final String TAG = "NetSniffer";
    private static final String CHANNEL = "netsniffer";

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;
    private int port = 8080;
    private String targetIp = "";  // 为空则记录所有

    private LogStore logStore;
    private PowerManager.WakeLock wakeLock;
    private int activeConnections = 0;
    private CountDownLatch stopLatch;

    // 广播结果给 Activity
    public interface ServiceListener {
        void onLogUpdated(LogEntry entry);
        void onStatusChanged(boolean running, int port, int activeConns);
    }

    private ServiceListener listener;

    public static final String ACTION_START = "com.netsniffer.START";
    public static final String ACTION_STOP = "com.netsniffer.STOP";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_IP = "targetIp";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        logStore = new LogStore(this);
        startForeground(1, buildNotification("NetSniffer 正在监听"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            if (ACTION_START.equals(intent.getAction())) {
                port = intent.getIntExtra(EXTRA_PORT, 8080);
                targetIp = intent.getStringExtra(EXTRA_IP);
                startListening();
            } else if (ACTION_STOP.equals(intent.getAction())) {
                stopListening();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopListening();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private class LocalBinder extends Binder {
        ProxyService getService() { return ProxyService.this; }
    }
    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setListener(ServiceListener l) {
        listener = l;
    }

    public String getLocalIp() {
        try {
            for (java.net.NetworkInterface ni : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress addr = ia.getAddress();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "未知";
    }

    // ---- 内部实现 ----

    private void startListening() {
        if (running) return;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            serverSocket.setSoTimeout(1000);
        } catch (IOException e) {
            Log.e(TAG, "无法绑定端口 " + port + ": " + e.getMessage());
            postStatus(false, port, 0);
            return;
        }

        pool = Executors.newCachedThreadPool();
        running = true;

        // Wake lock 防止休眠
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetSniffer");
        wakeLock.acquire();

        stopLatch = new CountDownLatch(1);
        postStatus(true, port, 0);

        pool.submit(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    int connId = ++activeConnections;
                    final int thisConn = connId;
                    pool.submit(() -> handleConnection(socket, thisConn));
                    postStatus(true, port, activeConnections);
                } catch (SocketTimeoutException ignored) {
                    // 循环继续
                } catch (IOException e) {
                    if (running) Log.e(TAG, "accept 异常: " + e.getMessage());
                }
            }
        });
    }

    private void stopListening() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        if (pool != null) pool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        activeConnections = 0;
        postStatus(false, port, 0);
    }

    private void handleConnection(Socket client, int connId) {
        long start = System.currentTimeMillis();
        LogEntry entry = new LogEntry();
        entry.timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        entry.isHttps = false;
        entry.tags = "conn#" + connId;
        entry.id = System.currentTimeMillis();
        entry.sourceIp = client.getRemoteSocketAddress() != null ?
                client.getRemoteSocketAddress().toString() : "unknown";

        if (targetIp != null && !targetIp.isEmpty() && !entry.sourceIp.contains(targetIp)) {
            Log.d(TAG, "过滤掉 " + entry.sourceIp + " (目标=" + targetIp + ")");
            try { client.close(); } catch (IOException ignored) {}
            return;
        }

        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 读取 HTTP 请求头
            StringBuilder reqLine = new StringBuilder();
            StringBuilder headers = new StringBuilder();
            int prev = 0;
            int totalHeaderBytes = 0;
            byte[] buf = new byte[8192];
            while (true) {
                int n = in.read(buf);
                if (n <= 0) break;
                totalHeaderBytes += n;
                String chunk = new String(buf, 0, n);
                if (reqLine.length() == 0) {
                    int nl = chunk.indexOf("\r\n");
                    reqLine.append(nl >= 0 ? chunk.substring(0, nl) : chunk);
                } else {
                    headers.append(chunk);
                    if (chunk.contains("\r\n\r\n")) break;
                }
            }

            String[] reqParts = reqLine.toString().split(" ", 3);
            String method = reqParts.length > 0 ? reqParts[0] : "?";
            String target = reqParts.length > 1 ? reqParts[1] : "";
            entry.method = method;

            if ("CONNECT".equalsIgnoreCase(method)) {
                // HTTPS 隧道模式：只记录 SNI，不加密解密
                entry.isHttps = true;
                entry.url = target;
                entry.status = "tunnel";

                forwardTunnel(client, target, entry);
            } else {
                // 普通 HTTP 转发
                String targetHost = parseHost(target, headers.toString());
                int targetPort = parsePort(target, targetHost, false);
                entry.url = target.startsWith("http") ? target : "http://" + target;

                forwardPlain(client, out, targetHost, targetPort,
                        reqLine.toString(), headers.toString(),
                        totalHeaderBytes, entry);
            }

            long duration = System.currentTimeMillis() - start;
            entry.duration = duration;

            logStore.add(entry);
            postLog(entry);

        } catch (Exception e) {
            Log.d(TAG, "连接处理: " + e.getMessage());
        } finally {
            activeConnections = Math.max(0, activeConnections - 1);
            postStatus(running, port, activeConnections);
        }
    }

    private void forwardPlain(Socket client, OutputStream out, String host, int port,
                              String reqLine, String headers, int hdrBytes, LogEntry entry)
            throws IOException {
        byte[] request = (reqLine + headers).getBytes();
        try (Socket target = new Socket()) {
            target.connect(new InetSocketAddress(host, port), 10000);
            // 发送原始请求给服务器
            target.getOutputStream().write(request);
            // 读取响应并转发给客户端
            byte[] buf = new byte[8192];
            int totalSent = 0;
            boolean headerDone = false;
            int contentLength = -1;
            while (true) {
                int n = target.getInputStream().read(buf);
                if (n <= 0) break;
                if (!headerDone) {
                    String h = new String(buf, 0, n);
                    int end = h.indexOf("\r\n\r\n");
                    if (end >= 0) {
                        headerDone = true;
                        String respHeaders = h.substring(0, end + 4);
                        contentLength = parseContentLength(respHeaders);
                        entry.reqSize = request.length;
                        entry.status = parseStatusFromHeader(respHeaders);
                        entry.tags = "REQ: " + reqLine.trim() + "\n" +
                                respHeaders.replaceAll("\r\n", " | ");
                        out.write(respHeaders.getBytes());
                        totalSent += respHeaders.length();
                    }
                } else {
                    entry.resSize = totalSent;
                }
                out.write(buf, 0, n);
                totalSent += n;
                if (contentLength >= 0 && totalSent >= contentLength + 1) break;
            }
            if (!entry.status.isEmpty()) entry.status += " from " + host;
            out.flush();
        }
    }

    private void forwardTunnel(Socket client, String target, LogEntry entry)
            throws IOException {
        String hostPort = target.replaceFirst("//", "");
        String host = hostPort.split(":")[0];
        int port = hostPort.contains(":") ? Integer.parseInt(hostPort.split(":")[1]) : 443;

        try (Socket target = new Socket()) {
            target.connect(new InetSocketAddress(host, port), 10000);
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
            client.getOutputStream().flush();

            // 双向隧道
            byte[] buf = new byte[8192];
            int totalA = 0, totalB = 0;

            // 客户端 → 目标
            final Socket targetRef = target;
            Thread t1 = new Thread(() -> {
                try {
                    int n;
                    while ((n = client.getInputStream().read(buf)) > 0) {
                        totalA += n;
                        targetRef.getOutputStream().write(buf, 0, n);
                        targetRef.getOutputStream().flush();
                    }
                } catch (IOException ignored) {}
                try { targetRef.shutdownOutput(); } catch (IOException ignored) {}
            });
            t1.start();

            int n;
            while ((n = target.getInputStream().read(buf)) > 0) {
                totalB += n;
                client.getOutputStream().write(buf, 0, n);
            }
            entry.resSize = totalB;
            entry.tags = "TLS隧道 " + host + ":" + port + " | 上行:" + totalA + " 下行:" + totalB;
            t1.join();
        }
    }

    private String parseHost(String target, String headers) {
        if (target.startsWith("http")) {
            try { return new URI(target).getHost(); } catch (Exception ignored) {}
        }
        // 从 Host 头取
        int idx = headers.toLowerCase().indexOf("host:");
        if (idx >= 0) {
            String v = headers.substring(idx + 5).trim();
            int nl = v.indexOf("\r\n");
            return nl >= 0 ? v.substring(0, nl).trim() : v.trim();
        }
        return target.split("/")[0];
    }

    private int parsePort(String target, String host, boolean https) {
        if (target.contains(":")) {
            try {
                String p = target.substring(target.lastIndexOf(":") + 1);
                if (p.matches("\\d+")) return Integer.parseInt(p);
            } catch (Exception ignored) {}
        }
        return https ? 443 : 80;
    }

    private int parseStatusCode(String headers) {
        int idx = headers.indexOf("HTTP/");
        if (idx >= 0) {
            try {
                int end = headers.indexOf("\r\n", idx);
                String line = headers.substring(idx, end > 0 ? end : headers.length());
                String[] p = line.split(" ");
                if (p.length > 1) return Integer.parseInt(p[1]);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private String parseStatusFromHeader(String headers) {
        int idx = headers.indexOf("HTTP/");
        if (idx >= 0) {
            try {
                int end = headers.indexOf("\r\n", idx);
                String line = headers.substring(idx, end > 0 ? end : headers.length());
                return line;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private int parseContentLength(String headers) {
        String h = headers.toLowerCase();
        int idx = h.indexOf("content-length:");
        if (idx >= 0) {
            int end = h.indexOf("\r\n", idx);
            if (end < 0) end = h.length();
            try { return Integer.parseInt(headers.substring(idx + 15, end).trim()); }
            catch (Exception ignored) {}
        }
        return -1;
    }

    private void postLog(LogEntry entry) {
        if (listener != null) listener.onLogUpdated(entry);
    }

    private void postStatus(boolean running, int port, int activeConns) {
        if (listener != null) listener.onStatusChanged(running, port, activeConns);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE);
        b.setContentTitle("NetSniffer 监听中");
        b.setContentText(text);
        b.setContentIntent(pi);
        b.setSmallIcon(android.R.drawable.ic_menu_compass);
        b.setOngoing(true);
        b.setCategory(Notification.CATEGORY_SERVICE);
        return b.build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "NetSniffer 服务", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("网络监听服务");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
}
