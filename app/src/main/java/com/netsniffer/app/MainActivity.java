package com.netsniffer.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity implements ProxyService.ServiceListener {

    private ProxyService service;
    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = (ProxyService) binder;
            service.setListener(MainActivity.this);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    private LinearLayout root;
    private TextView statusText;
    private EditText etPort, etFilterIp;
    private Button btnStart, btnStop, btnClear, btnExport;
    private ListView logList;
    private TextView logCount;
    private LogStore logStore;
    private LogAdapter adapter;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        logStore = new LogStore(this);
        buildUI();
        refreshLogList(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLogList(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (service != null) service.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (service != null) service.setListener(null);
        try { unbindService(conn); } catch (Exception ignored) {}
    }

    private void buildUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Title
        TextView title = new TextView(this);
        title.setText("\uD83D\uDCE1 NetSniffer 网络监听器");
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, dp(8));

        // Status card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackgroundColor(0xFF1E2A3B);
        statusCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusText = new TextView(this);
        statusText.setTextColor(0xFF7FC967);
        statusText.setTextSize(12);
        statusText.setText("● 未启动  |  IP: " + getLocalIp());
        statusCard.addView(statusText);

        // Input row: port + filter IP
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setBackgroundColor(0xFF1A2535);
        inputRow.setPadding(dp(4), dp(8), dp(4), dp(8));

        etPort = new EditText(this);
        etPort.setHint("端口 (默认8080)");
        etPort.setText("8080");
        etPort.setSingleLine(true);
        etPort.setTextColor(0xFFFFFFFF);
        inputRow.addView(etPort, new LinearLayout.LayoutParams(dp(90), -2));

        etFilterIp = new EditText(this);
        etFilterIp.setHint("过滤IP (空=全部)");
        etFilterIp.setSingleLine(true);
        etFilterIp.setTextColor(0xFFFFFFFF);
        inputRow.addView(etFilterIp, new LinearLayout.LayoutParams(0, -2, 1f));

        btnStart = new Button(this);
        btnStart.setText("启动");
        btnStart.setTextColor(0xFF7FC967);
        btnStart.setAllCaps(false);
        btnStart.setOnClickListener(v -> startService());

        btnStop = new Button(this);
        btnStop.setText("停止");
        btnStop.setTextColor(0xFFF47272);
        btnStop.setAllCaps(false);
        btnStop.setVisibility(View.GONE);
        btnStop.setOnClickListener(v -> stopService());

        inputRow.addView(btnStart, new LinearLayout.LayoutParams(-2, -2));
        inputRow.addView(btnStop, new LinearLayout.LayoutParams(-2, -2));

        // Action buttons row
        LinearLayout opRow = new LinearLayout(this);
        opRow.setOrientation(LinearLayout.HORIZONTAL);
        btnClear = new Button(this);
        btnClear.setText("清空日志");
        btnClear.setAllCaps(false);
        btnClear.setTextColor(0xFFFFFFFF);
        btnClear.setOnClickListener(v -> { logStore.clear(); refreshLogList(null); });

        btnExport = new Button(this);
        btnExport.setText("导出CSV");
        btnExport.setAllCaps(false);
        btnExport.setTextColor(0xFFFFFFFF);
        btnExport.setOnClickListener(v -> exportCsv());

        opRow.addView(btnClear, new LinearLayout.LayoutParams(0, -2, 1f));
        opRow.addView(btnExport, new LinearLayout.LayoutParams(0, -2, 1f));

        // Log count
        logCount = new TextView(this);
        logCount.setTextSize(11);
        logCount.setTextColor(0x88FFFFFF);
        logCount.setText("0 条记录");
        logCount.setPadding(dp(4), dp(4), dp(4), dp(0));

        // Log list
        logList = new ListView(this);
        logList.setDivider(null);
        logList.setDividerHeight(0);
        logList.setBackgroundColor(0xFF141E2B);

        root.addView(title);
        root.addView(statusCard);
        root.addView(inputRow);
        root.addView(opRow);
        root.addView(logCount);
        root.addView(logList, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void startService() {
        int port = Integer.parseInt(etPort.getText().toString().trim());
        String ip = etFilterIp.getText().toString().trim();
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_START);
        intent.putExtra(ProxyService.EXTRA_PORT, port);
        intent.putExtra(ProxyService.EXTRA_IP, ip);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent);
        else
            startService(intent);
        btnStart.setVisibility(View.GONE);
        btnStop.setVisibility(View.VISIBLE);
    }

    private void stopService() {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP);
        startService(intent);
        btnStart.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.GONE);
    }

    private void exportCsv() {
        try {
            String path = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() +
                    "/netsniffer_" + System.currentTimeMillis() + ".csv";
            File f = new File(path);
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            logStore.exportToFile(path);
            Toast.makeText(this, "已导出: " + path, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLogUpdated(LogEntry entry) {
        runOnUiThread(() -> refreshLogList(null));
    }

    @Override
    public void onStatusChanged(boolean running, int port, int activeConns) {
        runOnUiThread(() -> {
            String ip = getLocalIp();
            statusText.setText(running
                    ? "● 监听中  |  IP: " + ip + "  端口: " + port + "  连接: " + activeConns
                    : "● 未启动  |  IP: " + ip);
            statusText.setTextColor(running ? 0xFF7FC967 : 0xFFF47272);
            if (running) {
                btnStart.setVisibility(View.GONE);
                btnStop.setVisibility(View.VISIBLE);
            } else {
                btnStart.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.GONE);
            }
        });
    }

    private void refreshLogList(String ipFilter) {
        List<LogEntry> list = logStore.listAll();
        if (ipFilter != null && !ipFilter.isEmpty()) {
            List<LogEntry> filtered = new ArrayList<>();
            for (LogEntry e : list)
                if (e.sourceIp != null && e.sourceIp.contains(ipFilter))
                    filtered.add(e);
            list = filtered;
        }
        logCount.setText(list.size() + " 条记录");
        if (adapter == null)
            adapter = new LogAdapter(this, list);
        else {
            adapter.list = list;
            adapter.notifyDataSetChanged();
        }
        logList.setAdapter(adapter);
    }

    private String getLocalIp() {
        try {
            for (java.net.NetworkInterface ni :
                    Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InterfaceAddress ia : Collections.list(ni.getInterfaceAddresses())) {
                    java.net.InetAddress addr = ia.getAddress();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress())
                        return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "未知";
    }

    static class LogAdapter extends BaseAdapter {
        List<LogEntry> list = new ArrayList<>();
        final Context ctx;

        LogAdapter(Context ctx, List<LogEntry> list) {
            this.ctx = ctx;
            this.list = list;
        }

        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int i) { return list.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row;
            if (v == null) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
                row.setBackgroundColor(i % 2 == 0 ? 0xFF1E2A3B : 0xFF141E2B);
            } else {
                row = (LinearLayout) v;
                row.removeAllViews();
            }
            LogEntry e = list.get(i);
            TextView summary = new TextView(ctx);
            summary.setText(e.getSummary());
            summary.setTextColor(0xFFE0E0E0);
            summary.setTextSize(12);

            TextView meta = new TextView(ctx);
            meta.setText(e.timestamp + "  |  " + e.sourceIp +
                    "  req:" + e.reqSize + "B  res:" + e.resSize + "B" +
                    (e.duration > 0 ? "  " + e.duration + "ms" : ""));
            meta.setTextColor(0x88FFFFFF);
            meta.setTextSize(10);

            row.addView(summary);
            row.addView(meta);
            return row;
        }

        static int dp(Context c, int v) {
            return (int) (v * c.getResources().getDisplayMetrics().density);
        }
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
