package com.netsniffer.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class LogStore {
    private final DbHelper helper;

    public LogStore(Context ctx) {
        helper = new DbHelper(ctx);
    }

    public long add(LogEntry entry) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insert("entries", null, dbEntry(entry));
        db.close();
        return id;
    }

    public List<LogEntry> listAll() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<LogEntry> list = new ArrayList<>();
        Cursor c = db.query("entries", null, null, null, null, null, "id DESC", "1000");
        while (c.moveToNext()) list.add(readEntry(c));
        c.close();
        db.close();
        return list;
    }

    public List<LogEntry> listFiltered(String ipFilter) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<LogEntry> list = new ArrayList<>();
        String where = ipFilter == null || ipFilter.isEmpty() ? null : "source_ip LIKE ?";
        String[] args = ipFilter == null ? null : new String[]{"%" + ipFilter + "%"};
        Cursor c = db.query("entries", null, where, args, null, null, "id DESC", "1000");
        while (c.moveToNext()) list.add(readEntry(c));
        c.close();
        db.close();
        return list;
    }

    public void clear() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("entries", null, null);
        db.close();
    }

    public int count() {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM entries", null);
        c.moveToFirst();
        int count = c.getInt(0);
        c.close();
        db.close();
        return count;
    }

    private void copyRow(SQLiteDatabase from, long id, SQLiteDatabase to) {
        Cursor c = from.query("entries", null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        if (c.moveToFirst()) {
            to.insert("entries", null, dbRead(c));
        }
        c.close();
    }

    public void exportToFile(String path) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            java.io.File f = new java.io.File(path);
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write("id,timestamp,sourceIp,method,url,status,reqSize,resSize,duration,isHttps,tags\n");
            Cursor c = db.rawQuery("SELECT * FROM entries ORDER BY id ASC", null);
            while (c.moveToNext()) {
                LogEntry e = readEntry(c);
                w.write(e.id + "," + csv(e.timestamp) + "," + csv(e.sourceIp) + "," +
                        csv(e.method) + "," + csv(e.url) + "," + csv(e.status) + "," +
                        e.reqSize + "," + e.resSize + "," + e.duration + "," +
                        (e.isHttps ? 1 : 0) + "," + csv(e.tags) + "\n");
            }
            c.close();
            w.close();
        } catch (Exception ignored) {}
        db.close();
    }

    private static android.content.ContentValues dbEntry(LogEntry e) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("timestamp", e.timestamp);
        v.put("source_ip", e.sourceIp);
        v.put("method", e.method);
        v.put("url", e.url);
        v.put("status", e.status);
        v.put("req_size", e.reqSize);
        v.put("res_size", e.resSize);
        v.put("duration", e.duration);
        v.put("is_https", e.isHttps ? 1 : 0);
        v.put("tags", e.tags);
        return v;
    }

    private static android.content.ContentValues dbRead(Cursor c) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("id", c.getLong(0));
        v.put("timestamp", c.getString(1));
        v.put("source_ip", c.getString(2));
        v.put("method", c.getString(3));
        v.put("url", c.getString(4));
        v.put("status", c.getString(5));
        v.put("req_size", c.getInt(6));
        v.put("res_size", c.getInt(7));
        v.put("duration", c.getLong(8));
        v.put("is_https", c.getInt(9));
        v.put("tags", c.getString(10));
        return v;
    }

    private static LogEntry readEntry(Cursor c) {
        LogEntry e = new LogEntry();
        e.id = c.getLong(0);
        e.timestamp = c.getString(1);
        e.sourceIp = c.getString(2);
        e.method = c.getString(3);
        e.url = c.getString(4);
        e.status = c.getString(5);
        e.reqSize = c.getInt(6);
        e.resSize = c.getInt(7);
        e.duration = c.getLong(8);
        e.isHttps = c.getInt(9) == 1;
        e.tags = c.getString(10);
        return e;
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) {
            super(ctx, "netsniffer.db", null, 1);
        }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp TEXT, source_ip TEXT, method TEXT, url TEXT," +
                "status TEXT, req_size INTEGER, res_size INTEGER," +
                "duration INTEGER, is_https INTEGER, tags TEXT)");
            db.execSQL("CREATE INDEX idx_ip ON entries(source_ip)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int old, int nw) {}
    }
}
