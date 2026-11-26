package app.gitwatch.notifier;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.config.TopicSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class KafkaDlqPublisher implements DlqPublisher {

    private final TopicSender sender;
    private final ObjectMapper mapper;

    public KafkaDlqPublisher(TopicSender sender, ObjectMapper mapper) {
        this.sender = sender;
        this.mapper = mapper;
    }

    @Override
    public void publish(DeliveryFailure failure) {
        ObjectNode node = mapper.createObjectNode();
        node.set("event", mapper.valueToTree(failure.event()));
        node.put("subscription_id", failure.subscriptionId());
        node.put("error", failure.error());
        node.put("failed_at", failure.failedAt().toString());
        try {
            sender.send(KafkaTopics.NOTIFICATIONS_DLQ, failure.subscriptionId(), mapper.writeValueAsString(node));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to publish notification dlq", ex);
        }
    }
}
