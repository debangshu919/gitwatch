package app.gitwatch.notifier;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.poller.EventJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {

    private final NotificationService notifications;
    private final ObjectMapper mapper;

    public NotificationWorker(NotificationService notifications, ObjectMapper mapper) {
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaTopics.GITHUB_EVENTS, groupId = "gitwatch-notifier")
    public void onEvent(String payload) {
        GitHubEvent event = EventJson.read(mapper, payload);
        notifications.handle(event);
    }
}
