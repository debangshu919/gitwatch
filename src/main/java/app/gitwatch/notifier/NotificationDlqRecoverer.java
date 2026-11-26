package app.gitwatch.notifier;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.config.TopicSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

public final class NotificationDlqRecoverer implements ConsumerRecordRecoverer {

    private final TopicSender sender;
    private final ObjectMapper mapper;
    private final Clock clock;

    public NotificationDlqRecoverer(TopicSender sender, ObjectMapper mapper, Clock clock) {
        this.sender = sender;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        String key = record.key() == null ? null : record.key().toString();
        String payload = record.value() == null ? null : record.value().toString();
        String body = DlqEnvelope.write(mapper, null, payload, null, message(exception), clock.instant());
        try {
            sender.send(KafkaTopics.NOTIFICATIONS_DLQ, key, body);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to publish notification dlq", ex);
        }
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }
}
