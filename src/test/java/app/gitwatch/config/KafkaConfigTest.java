package app.gitwatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import app.gitwatch.notifier.DiscordThrottleException;
import app.gitwatch.notifier.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

class KafkaConfigTest {

    @Test
    void throttleBacksOffWithoutDeadLetter() {
        AtomicInteger dlq = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        DefaultErrorHandler handler = handler(dlq, sleeps);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("github.events", 0, 4L, "k", "v");
        DiscordThrottleException throttle = new DiscordThrottleException(Duration.ofSeconds(40));
        Exception wrapped = new ListenerExecutionFailedException("listener failed", throttle);
        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = mock(Consumer.class);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                wrapped, List.of(record), consumer, null));

        assertEquals("org.springframework.kafka.listener.RecordInRetryException", thrown.getClass().getName());
        assertEquals(0, dlq.get());
        assertEquals(List.of(40_000L), sleeps);
        assertTrue(handler.seeksAfterHandling());
        assertFalse(throttle.getMessage().contains("http"));
        verify(consumer).seek(new TopicPartition("github.events", 0), 4L);
    }

    @Test
    void throttleSleepIsCappedUnderThePollInterval() {
        AtomicInteger dlq = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        DefaultErrorHandler handler = handler(dlq, sleeps);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("github.events", 0, 1L, "k", "v");

        assertFalse(handler.handleOne(
                new DiscordThrottleException(Duration.ofMinutes(10)), record, null, null));

        assertEquals(0, dlq.get());
        assertEquals(List.of(NotificationService.MAX_SLEEP.toMillis()), sleeps);
        assertTrue(NotificationService.MAX_SLEEP.toMillis() < 300_000L);
    }

    @Test
    void otherFailuresStillDeadLetterAfterTwoRetries() {
        AtomicInteger dlq = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        DefaultErrorHandler handler = handler(dlq, sleeps);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("github.events", 1, 9L, "k", "v");
        IllegalStateException failure = new IllegalStateException("mongo down");
        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = mock(Consumer.class);

        assertThrows(RuntimeException.class, () -> handler.handleRemaining(failure, List.of(record), consumer, null));
        assertThrows(RuntimeException.class, () -> handler.handleRemaining(failure, List.of(record), consumer, null));
        handler.handleRemaining(failure, List.of(record), consumer, null);

        assertEquals(1, dlq.get());
        assertEquals(List.of(1_000L, 1_000L), sleeps);
    }

    private static DefaultErrorHandler handler(AtomicInteger dlq, List<Long> sleeps) {
        return KafkaConfig.notifierErrorHandler(
                (topic, key, value) -> dlq.incrementAndGet(),
                new ObjectMapper(),
                Clock.systemUTC(),
                new BackOffHandler() {
                    @Override
                    public void onNextBackOff(MessageListenerContainer container, Exception exception, long nextBackOff) {
                        sleeps.add(nextBackOff);
                    }
                });
    }
}
