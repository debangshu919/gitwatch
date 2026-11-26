package app.gitwatch.config;

@FunctionalInterface
public interface TopicSender {

    void send(String topic, String key, String value) throws Exception;
}
