package app.gitwatch.poller;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.config.TopicSender;
import app.gitwatch.model.GitHubEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher implements EventPublisher {

    private final TopicSender sender;
    private final ObjectMapper mapper;

    public KafkaEventPublisher(TopicSender sender, ObjectMapper mapper) {
        this.sender = sender;
        this.mapper = mapper;
    }

    @Override
    public void publish(GitHubEvent event) {
        try {
            sender.send(KafkaTopics.GITHUB_EVENTS, event.owner() + "/" + event.repo(), EventJson.write(mapper, event));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to publish github event", ex);
        }
    }
}
