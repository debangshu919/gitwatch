package app.gitwatch.notifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.config.TopicSender;
import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.poller.EventJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class DlqEnvelopeTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private final ObjectMapper mapper = EventJson.mapper();

    @Test
    void deliveryFailureAndListenerFailureShareOneShape() throws Exception {
        GitHubEvent event = event();
        RecordingSender sender = new RecordingSender();
        new KafkaDlqPublisher(sender, mapper).publish(
                new DeliveryFailure(event, "22222222-2222-2222-2222-222222222222", "Discord returned 400", NOW));

        JsonNode delivery = mapper.readTree(sender.value);
        assertEquals(KafkaTopics.NOTIFICATIONS_DLQ, sender.topic);
        assertEnvelope(delivery, "22222222-2222-2222-2222-222222222222", "Discord returned 400");
        assertEquals(1423, delivery.get("event").get("number").asInt());
        assertFalse(delivery.has("event_type"));

        String raw = EventJson.write(mapper, event);
        sender.value = null;
        new NotificationDlqRecoverer(sender, mapper, Clock.fixed(NOW, ZoneOffset.UTC))
                .accept(new ConsumerRecord<>(KafkaTopics.GITHUB_EVENTS, 0, 0L, "apache/iggy", raw), new IllegalStateException("listener failed"));

        JsonNode recovered = mapper.readTree(sender.value);
        assertEquals(KafkaTopics.NOTIFICATIONS_DLQ, sender.topic);
        assertEquals("apache/iggy", sender.key);
        assertEnvelope(recovered, null, "listener failed");
        assertEquals("issue.opened", recovered.get("event").get("event_type").asText());
        assertEquals(1423, recovered.get("event").get("number").asInt());
        assertFalse(recovered.has("event_type"));
    }

    @Test
    void unreadableListenerPayloadStillUsesTheEnvelope() throws Exception {
        RecordingSender sender = new RecordingSender();
        new NotificationDlqRecoverer(sender, mapper, Clock.fixed(NOW, ZoneOffset.UTC))
                .accept(new ConsumerRecord<>(KafkaTopics.GITHUB_EVENTS, 0, 0L, null, "not-json"), new IllegalArgumentException("failed to decode github event"));

        JsonNode recovered = mapper.readTree(sender.value);
        assertEnvelope(recovered, null, "failed to decode github event");
        assertTrue(recovered.get("event").isNull());
    }

    private static void assertEnvelope(JsonNode node, String subscriptionId, String error) {
        assertTrue(node.has("event"));
        assertTrue(node.has("subscription_id"));
        assertTrue(node.has("error"));
        assertTrue(node.has("failed_at"));
        assertEquals(4, node.size());
        if (subscriptionId == null) {
            assertTrue(node.get("subscription_id").isNull());
        } else {
            assertEquals(subscriptionId, node.get("subscription_id").asText());
        }
        assertEquals(error, node.get("error").asText());
        assertEquals(NOW.toString(), node.get("failed_at").asText());
    }

    private static GitHubEvent event() {
        return new GitHubEvent(
                EventTypes.ISSUE_OPENED,
                "apache",
                "iggy",
                1423,
                "Add support for streaming queries",
                "username",
                List.of("good first issue"),
                "https://github.com/apache/iggy/issues/1423",
                "First 200 characters...",
                Instant.parse("2026-11-17T03:00:00Z"));
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
