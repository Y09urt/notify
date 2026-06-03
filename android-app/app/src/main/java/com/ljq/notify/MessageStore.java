package com.ljq.notify;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class MessageStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "notify.db";
    private static final int DB_VERSION = 1;

    public MessageStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (" +
                "id TEXT PRIMARY KEY," +
                "title TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "data TEXT," +
                "created_at INTEGER NOT NULL" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void save(NotifyMessage message) {
        ContentValues values = new ContentValues();
        values.put("id", message.id);
        values.put("title", message.title);
        values.put("body", message.body);
        values.put("data", message.data);
        values.put("created_at", message.createdAt);
        getWritableDatabase().insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<NotifyMessage> latest(int limit) {
        ArrayList<NotifyMessage> messages = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "messages",
                new String[]{"id", "title", "body", "data", "created_at"},
                null,
                null,
                null,
                null,
                "created_at DESC",
                String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                messages.add(new NotifyMessage(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4)));
            }
        }
        return messages;
    }
}
