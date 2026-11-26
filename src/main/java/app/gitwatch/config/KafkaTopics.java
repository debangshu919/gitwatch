package app.gitwatch.config;

public final class KafkaTopics {

    public static final String GITHUB_EVENTS = "github.events";
    public static final String NOTIFICATIONS_DLQ = "notifications.dlq";

    private KafkaTopics() {}
}
