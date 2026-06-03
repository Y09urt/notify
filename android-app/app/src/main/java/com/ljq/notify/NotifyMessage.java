package com.ljq.notify;

public class NotifyMessage {
    public final String id;
    public final String title;
    public final String body;
    public final String data;
    public final long createdAt;

    public NotifyMessage(String id, String title, String body, String data, long createdAt) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.data = data;
        this.createdAt = createdAt;
    }
}
