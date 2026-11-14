package com.netsniffer.app;

public class LogEntry {
    public long id;
    public String timestamp;
    public String sourceIp;
    public String method;
    public String url;
    public String status;
    public int reqSize;
    public int resSize;
    public long duration;
    public boolean isHttps;
    public String tags;

    public LogEntry() {}

    public String getSummary() {
        if (isHttps) return "[HTTPS] " + sourceIp + " → " + url;
        return "[" + method + "] " + sourceIp + " " + url + " " + status;
    }

    public String getDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ").append(timestamp).append("\n");
        sb.append("来源IP: ").append(sourceIp).append("\n");
        sb.append("方法: ").append(method).append("\n");
        sb.append("URL: ").append(url).append("\n");
        if (!isHttps) sb.append("状态: ").append(status).append("\n");
        sb.append("请求: ").append(reqSize).append("B  响应: ").append(resSize).append("B");
        if (duration > 0) sb.append("  耗时: ").append(duration).append("ms");
        sb.append("\n").append(tags);
        return sb.toString();
    }
}
