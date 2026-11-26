package app.gitwatch.config;

import app.gitwatch.notifier.NotificationDlqRecoverer;
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
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic githubEventsTopic() {
        return TopicBuilder.name(KafkaTopics.GITHUB_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationsDlqTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS_DLQ)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public TopicSender topicSender(KafkaTemplate<String, String> kafkaTemplate) {
        return (topic, key, value) -> kafkaTemplate.send(topic, key, value).get(10, TimeUnit.SECONDS);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, TopicSender sender, ObjectMapper mapper, Clock clock) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new NotificationDlqRecoverer(sender, mapper, clock), new FixedBackOff(1_000L, 2L)));
        return factory;
    }
}
