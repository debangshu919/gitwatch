package app.gitwatch.config;

import app.gitwatch.notifier.DiscordThrottleException;
import app.gitwatch.notifier.NotificationDlqRecoverer;
import app.gitwatch.notifier.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BackOffHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultBackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic githubEventsTopic() {
        return TopicBuilder.name(KafkaTopics.GITHUB_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic notificationsDlqTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS_DLQ).partitions(3).replicas(1).build();
    }

    @Bean
    public TopicSender topicSender(KafkaTemplate<String, String> kafkaTemplate) {
        return (topic, key, value) -> kafkaTemplate.send(topic, key, value).get(10, TimeUnit.SECONDS);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            TopicSender sender,
            ObjectMapper mapper,
            Clock clock) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(notifierErrorHandler(sender, mapper, clock));
        return factory;
    }

    static DefaultErrorHandler notifierErrorHandler(TopicSender sender, ObjectMapper mapper, Clock clock) {
        return notifierErrorHandler(sender, mapper, clock, new DefaultBackOffHandler());
    }

    // A 429 that escapes the listener is still a throttle. Back off and leave the offset
    // uncommitted. Two one-second retries then the DLQ recoverer would drop the alert.
    static DefaultErrorHandler notifierErrorHandler(
            TopicSender sender, ObjectMapper mapper, Clock clock, BackOffHandler backOffHandler) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new NotificationDlqRecoverer(sender, mapper, clock),
                new FixedBackOff(1_000L, 2L),
                backOffHandler);
        handler.setBackOffFunction((record, exception) -> backoffFor(exception));
        handler.addRetryableExceptions(DiscordThrottleException.class);
        return handler;
    }

    static BackOff backoffFor(Exception exception) {
        DiscordThrottleException throttle = throttleOf(exception);
        if (throttle == null) {
            return null;
        }
        long delay = NotificationService.capSleep(throttle.retryAfter()).toMillis();
        return new FixedBackOff(delay, FixedBackOff.UNLIMITED_ATTEMPTS);
    }

    static DiscordThrottleException throttleOf(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DiscordThrottleException throttle) {
                return throttle;
            }
            current = current.getCause();
        }
        return null;
    }
}
