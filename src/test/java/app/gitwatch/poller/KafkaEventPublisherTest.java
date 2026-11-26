package app.gitwatch.poller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.config.TopicSender;
import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KafkaEventPublisherTest {

    @Test
    void publishesTheSpecPayloadToGithubEvents() throws Exception {
        ObjectMapper mapper = EventJson.mapper();
        RecordingSender sender = new RecordingSender();
        KafkaEventPublisher publisher = new KafkaEventPublisher(sender, mapper);
        publisher.publish(new GitHubEvent(
                EventTypes.ISSUE_OPENED,
                "apache",
                "iggy",
                1423,
                "Add support for streaming queries",
                "username",
                List.of("good first issue"),
                "https://github.com/apache/iggy/issues/1423",
                "First 200 characters...",
                Instant.parse("2026-11-17T03:00:00Z")));

        assertEquals(KafkaTopics.GITHUB_EVENTS, sender.topic);
        assertEquals("apache/iggy", sender.key);
        JsonNode actual = mapper.readTree(sender.value);
        assertEquals("issue.opened", actual.get("event_type").asText());
        assertEquals(1423, actual.get("number").asInt());
        assertEquals("First 200 characters...", actual.get("body_excerpt").asText());
    }

    private static final class RecordingSender implements TopicSender {
        private String topic;
        private String key;
        private String value;

        @Override
        public void send(String topic, String key, String value) {
            this.topic = topic;
            this.key = key;
            this.value = value;
        }
    }
}
