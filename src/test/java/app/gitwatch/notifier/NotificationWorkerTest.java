package app.gitwatch.notifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.gitwatch.config.KafkaTopics;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.poller.EventJson;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

class NotificationWorkerTest {

    @Test
    void listensOnGithubEventsAndDelegates() throws Exception {
        KafkaListener listener = NotificationWorker.class.getMethod("onEvent", String.class).getAnnotation(KafkaListener.class);
        assertEquals(KafkaTopics.GITHUB_EVENTS, listener.topics()[0]);

        CapturingService service = new CapturingService();
        NotificationWorker worker = new NotificationWorker(service, EventJson.mapper());
        worker.onEvent("""
                {"event_type":"issue.opened","owner":"apache","repo":"iggy","number":1423,"title":"Add support for streaming queries","author":"username","labels":["good first issue"],"url":"https://github.com/apache/iggy/issues/1423","body_excerpt":"First 200 characters...","created_at":"2026-11-17T03:00:00Z"}
                """);
        assertEquals(1423, service.event.number());
        assertEquals("apache", service.event.owner());
    }

    private static final class CapturingService extends NotificationService {
        private GitHubEvent event;

        CapturingService() {
            super(null, null, null, null, null, null, 1, null, null);
        }

        @Override
        public void handle(GitHubEvent event) {
            this.event = event;
        }
    }
}
