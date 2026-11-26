package app.gitwatch.notifier;

import app.gitwatch.model.NotificationLog;

public interface NotificationLogStore {

    void append(NotificationLog log);
}
