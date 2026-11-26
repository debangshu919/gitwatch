package app.gitwatch.notifier;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.config.TopicSender;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        String body = DlqEnvelope.write(
                mapper,
                failure.event(),
                null,
                failure.subscriptionId(),
                failure.error(),
                failure.failedAt());
        try {
            sender.send(KafkaTopics.NOTIFICATIONS_DLQ, failure.subscriptionId(), body);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to publish notification dlq", ex);
        }
    }
}
