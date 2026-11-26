package app.gitwatch.notifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.model.NotificationLog;
import app.gitwatch.service.DeliveryTarget;
import app.gitwatch.service.SubscriptionLookup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUBSCRIPTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHANNEL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private MockWebServer server;
    private MemoryLookup lookup;
    private MemoryLogs logs;
    private MemoryDlq dlq;
    private RecordingSleeper sleeper;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> bodies = new ArrayList<>();
    private int nextStatus = 204;
    private NotificationService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                bodies.add(request.getBody().readUtf8());
                return new MockResponse().setResponseCode(nextStatus);
            }
        });
        server.start();
        lookup = new MemoryLookup();
        logs = new MemoryLogs();
        dlq = new MemoryDlq();
        sleeper = new RecordingSleeper();
        DiscordClient discord = new DiscordClient(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                mapper,
                Duration.ofSeconds(2));
        service = new NotificationService(lookup, discord, logs, dlq, Clock.fixed(NOW, ZoneOffset.UTC), sleeper, 3, Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void labelFilterSkipsMissingAndMatchesPresent() throws Exception {
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of("good first issue")));

        service.handle(issue(List.of("bug")));
        assertEquals(0, calls.get());
        assertTrue(logs.entries.isEmpty());

        service.handle(issue(List.of("good first issue", "help wanted")));
        assertEquals(1, calls.get());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
        assertEquals(specContent(EventTypes.ISSUE_OPENED, List.of("good first issue", "help wanted")), content(bodies.get(0)));
    }

    @Test
    void severalRequiredLabelsAreAnd() {
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of("bug", "good first issue")));
        service.handle(issue(List.of("bug")));
        assertEquals(0, calls.get());

        service.handle(issue(List.of("good first issue", "bug")));
        assertEquals(1, calls.get());
    }

    @Test
    void emptyFilterMatchesEveryEventOfThatType() {
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(discord(EventTypes.PR_OPENED, List.of()));

        service.handle(issue(List.of()));
        service.handle(pull(List.of("bug")));

        assertEquals(2, calls.get());
        assertEquals(EventTypes.ISSUE_OPENED, lookup.seenTypes.get(0));
        assertEquals(EventTypes.PR_OPENED, lookup.seenTypes.get(1));
    }

    @Test
    void emptyFilterDoesNotCrossEventTypes() {
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        service.handle(pull(List.of()));
        assertEquals(0, calls.get());
    }

    @Test
    void discordPayloadMatchesFormat() throws Exception {
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        service.handle(issue(List.of("good first issue", "help wanted")));
        assertEquals(specContent(EventTypes.ISSUE_OPENED, List.of("good first issue", "help wanted")), content(bodies.get(0)));
        assertFalse(bodies.get(0).contains("telegram"));
    }

    @Test
    void pullRequestPayloadUsesPullRequestCopyAndUrl() throws Exception {
        lookup.targets.add(discord(EventTypes.PR_OPENED, List.of()));
        service.handle(pull(List.of()));
        String content = content(bodies.get(0));
        assertTrue(content.startsWith("🟢 New Pull Request in apache/iggy"));
        assertTrue(content.contains("https://github.com/apache/iggy/pull/1423"));
        assertFalse(content.contains("Labels:"));
    }

    @Test
    void telegramChannelIsNeverCalled() {
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(new DeliveryTarget(
                USER_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "telegram",
                true,
                server.url("/telegram").toString(),
                EventTypes.ISSUE_OPENED,
                List.of()));

        service.handle(issue(List.of("good first issue")));

        assertEquals(1, calls.get());
        assertTrue(bodies.get(0).contains("New Issue"));
        assertEquals(1, logs.entries.size());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
    }

    @Test
    void discord4xxIsLoggedFailedAndNotRetried() {
        nextStatus = 400;
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        service.handle(issue(List.of()));

        assertEquals(1, calls.get());
        assertEquals(0, sleeper.calls);
        assertEquals(1, logs.entries.size());
        NotificationLog entry = logs.entries.get(0);
        assertEquals(NotificationLog.FAILED, entry.getStatus());
        assertEquals(USER_ID.toString(), entry.getUserId());
        assertEquals(SUBSCRIPTION_ID.toString(), entry.getSubscriptionId());
        assertEquals("https://github.com/apache/iggy/issues/1423", entry.getGithubUrl());
        assertEquals("Add support for streaming queries", entry.getTitle());
        assertTrue(entry.getError().contains("400"));
        assertEquals(1, dlq.failures.size());
        assertEquals(SUBSCRIPTION_ID.toString(), dlq.failures.get(0).subscriptionId());
    }

    @Test
    void discord5xxIsRetried() {
        nextStatus = 500;
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int attempt = calls.incrementAndGet();
                bodies.add(request.getBody().readUtf8());
                return new MockResponse().setResponseCode(attempt == 1 ? 500 : 204);
            }
        });
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        service.handle(issue(List.of()));

        assertEquals(2, calls.get());
        assertEquals(1, sleeper.calls);
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
        assertNull(logs.entries.get(0).getError());
        assertTrue(dlq.failures.isEmpty());
    }

    @Test
    void discord5xxDoesNotRetryForever() {
        nextStatus = 500;
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        service.handle(issue(List.of()));

        assertEquals(3, calls.get());
        assertEquals(2, sleeper.calls);
        assertEquals(NotificationLog.FAILED, logs.entries.get(0).getStatus());
        assertEquals(1, dlq.failures.size());
        assertTrue(dlq.failures.get(0).error().contains("500"));
    }

    @Test
    void invalidWebhookIsPermanentAndDoesNotCallDiscord() {
        lookup.targets.add(new DeliveryTarget(
                USER_ID, SUBSCRIPTION_ID, CHANNEL_ID, "discord", true, "not a url", EventTypes.ISSUE_OPENED, List.of()));
        service.handle(issue(List.of()));
        assertEquals(0, calls.get());
        assertEquals(NotificationLog.FAILED, logs.entries.get(0).getStatus());
        assertEquals(1, dlq.failures.size());
    }

    private String content(String body) throws Exception {
        JsonNode node = mapper.readTree(body);
        return node.get("content").asText();
    }

    private String specContent(String eventType, List<String> labels) {
        GitHubEvent event = EventTypes.PR_OPENED.equals(eventType) ? pull(labels) : issue(labels);
        return DiscordMessageFormatter.format(event);
    }

    private DeliveryTarget discord(String eventType, List<String> labels) {
        return new DeliveryTarget(
                USER_ID,
                SUBSCRIPTION_ID,
                CHANNEL_ID,
                "discord",
                true,
                server.url("/webhook").toString(),
                eventType,
                labels);
    }

    private static GitHubEvent issue(List<String> labels) {
        return new GitHubEvent(
                EventTypes.ISSUE_OPENED,
                "apache",
                "iggy",
                1423,
                "Add support for streaming queries",
                "username",
                labels,
                "https://github.com/apache/iggy/issues/1423",
                "First 200 characters...",
                Instant.parse("2026-11-17T03:00:00Z"));
    }

    private static GitHubEvent pull(List<String> labels) {
        return new GitHubEvent(
                EventTypes.PR_OPENED,
                "apache",
                "iggy",
                1423,
                "Add support for streaming queries",
                "username",
                labels,
                "https://github.com/apache/iggy/pull/1423",
                "",
                Instant.parse("2026-11-17T03:00:00Z"));
    }

    private static final class MemoryLookup implements SubscriptionLookup {
        private final List<DeliveryTarget> targets = new ArrayList<>();
        private final List<String> seenTypes = new ArrayList<>();

        @Override
        public List<DeliveryTarget> findActive(String owner, String repo, String eventType) {
            seenTypes.add(eventType);
            return targets.stream().filter(target -> target.eventType().equals(eventType)).toList();
        }
    }

    private static final class MemoryLogs implements NotificationLogStore {
        private final List<NotificationLog> entries = new ArrayList<>();

        @Override
        public void append(NotificationLog log) {
            entries.add(log);
        }
    }

    private static final class MemoryDlq implements DlqPublisher {
        private final List<DeliveryFailure> failures = new ArrayList<>();

        @Override
        public void publish(DeliveryFailure failure) {
            failures.add(failure);
        }
    }

    private static final class RecordingSleeper implements Sleeper {
        private int calls;

        @Override
        public void sleep(Duration duration) {
            calls++;
        }
    }
}
