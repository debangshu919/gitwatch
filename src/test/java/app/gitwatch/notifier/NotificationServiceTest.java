package app.gitwatch.notifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.model.NotificationLog;
import app.gitwatch.service.DedupKeys;
import app.gitwatch.service.DedupStore;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private MemoryDeliveries deliveries;
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
        deliveries = new MemoryDeliveries();
        sleeper = new RecordingSleeper();
        Duration backoff = Duration.ofMillis(1);
        DiscordClient discord = new DiscordClient(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                mapper,
                Duration.ofSeconds(2),
                backoff);
        service = new NotificationService(
                lookup, discord, logs, dlq, Clock.fixed(NOW, ZoneOffset.UTC), sleeper, 3, backoff, deliveries);
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
    void discord429SleepsRetryAfterAndDoesNotDlqOnFirstResponse() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int attempt = calls.incrementAndGet();
                bodies.add(request.getBody().readUtf8());
                if (attempt == 1) {
                    return new MockResponse()
                            .setResponseCode(429)
                            .setHeader("Retry-After", "1.5")
                            .setBody("{\"message\":\"You are being rate limited.\",\"retry_after\":9,\"global\":false}");
                }
                return new MockResponse().setResponseCode(204);
            }
        });
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));

        service.handle(issue(List.of()));

        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofMillis(1500)), sleeper.slept);
        assertEquals(1, logs.entries.size());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
        assertNull(logs.entries.get(0).getError());
        assertTrue(dlq.failures.isEmpty());
    }

    @Test
    void discord429UsesJsonRetryAfterWhenHeaderIsMissing() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int attempt = calls.incrementAndGet();
                if (attempt == 1) {
                    return new MockResponse()
                            .setResponseCode(429)
                            .setBody("{\"retry_after\":1.25}");
                }
                return new MockResponse().setResponseCode(204);
            }
        });
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));

        service.handle(issue(List.of()));

        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofMillis(1250)), sleeper.slept);
        assertTrue(dlq.failures.isEmpty());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
    }

    @Test
    void discord429WithoutDelaySleepsNotifierBackoff() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int attempt = calls.incrementAndGet();
                if (attempt == 1) {
                    return new MockResponse().setResponseCode(429);
                }
                return new MockResponse().setResponseCode(204);
            }
        });
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));

        service.handle(issue(List.of()));

        assertEquals(List.of(Duration.ofMillis(1)), sleeper.slept);
        assertTrue(dlq.failures.isEmpty());
    }

    @Test
    void discord429LongerThanBudgetIsNotFailed() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "60");
            }
        });
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));

        DiscordThrottleException ex = assertThrows(DiscordThrottleException.class, () -> service.handle(issue(List.of())));

        assertEquals(1, calls.get());
        assertEquals(0, sleeper.calls);
        assertTrue(logs.entries.isEmpty());
        assertTrue(dlq.failures.isEmpty());
        assertEquals(Duration.ofSeconds(60), ex.retryAfter());
        assertFalse(ex.getMessage().contains("http"));
        assertFalse(ex.getMessage().contains(server.getHostName()));
    }

    @Test
    void discord429DoesNotFailWhenAttemptsAreExhausted() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "1");
            }
        });
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));

        DiscordThrottleException ex = assertThrows(DiscordThrottleException.class, () -> service.handle(issue(List.of())));

        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), sleeper.slept);
        assertTrue(logs.entries.isEmpty());
        assertTrue(dlq.failures.isEmpty());
        assertEquals(Duration.ofSeconds(1), ex.retryAfter());
    }

    @Test
    void throttleSleepBudgetIsSharedAndHandlerWaitStaysInsideThePollInterval() {
        UUID paced = UUID.fromString("44444444-4444-4444-4444-444444444444");
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                String retryAfter = request.getPath().contains("paced") ? "10" : "600";
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", retryAfter);
            }
        });
        lookup.targets.add(new DeliveryTarget(
                USER_ID, SUBSCRIPTION_ID, CHANNEL_ID, "discord", true,
                server.url("/long").toString(), EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(new DeliveryTarget(
                USER_ID, paced, CHANNEL_ID, "discord", true,
                server.url("/paced").toString(), EventTypes.ISSUE_OPENED, List.of()));

        DiscordThrottleException ex = assertThrows(DiscordThrottleException.class, () -> service.handle(issue(List.of())));

        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(10)), sleeper.slept);
        assertEquals(4, calls.get());
        assertTrue(dlq.failures.isEmpty());
        assertEquals(NotificationService.MAX_SLEEP.minus(Duration.ofSeconds(20)), ex.retryAfter());
        assertFalse(ex.getMessage().contains("http"));
    }

    @Test
    void pendingThrottleIsNotDiscardedWhenAnotherTargetCannotBeLogged() {
        logs.failWrites = true;
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "60");
            }
        });
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(new DeliveryTarget(
                USER_ID,
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                CHANNEL_ID,
                "discord",
                true,
                "not a url",
                EventTypes.ISSUE_OPENED,
                List.of()));

        assertThrows(DiscordThrottleException.class, () -> service.handle(issue(List.of())));

        assertEquals(1, calls.get());
        assertTrue(dlq.failures.isEmpty());
    }

    @Test
    void deliveryStoreOutageIsNotAcknowledged() {
        deliveries.failReads = true;
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));

        DeliveryStoreException ex = assertThrows(DeliveryStoreException.class, () -> service.handle(issue(List.of())));

        assertEquals(0, calls.get());
        assertTrue(logs.entries.isEmpty());
        assertTrue(dlq.failures.isEmpty());
        assertFalse(ex.getMessage().contains("http"));
    }

    @Test
    void throttleReplayDoesNotPostAgainToATerminalWebhook() {
        UUID paced = UUID.fromString("44444444-4444-4444-4444-444444444444");
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                if (request.getPath().contains("gone")) {
                    return new MockResponse().setResponseCode(404);
                }
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "60");
            }
        });
        lookup.targets.add(new DeliveryTarget(
                USER_ID, SUBSCRIPTION_ID, CHANNEL_ID, "discord", true,
                server.url("/gone").toString(), EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(new DeliveryTarget(
                USER_ID, paced, CHANNEL_ID, "discord", true,
                server.url("/paced").toString(), EventTypes.ISSUE_OPENED, List.of()));
        GitHubEvent event = issue(List.of());

        assertThrows(DiscordThrottleException.class, () -> service.handle(event));
        assertThrows(DiscordThrottleException.class, () -> service.handle(event));

        assertEquals(3, calls.get());
        assertEquals(1, dlq.failures.size());
        assertEquals(1, logs.entries.size());
        assertEquals(NotificationLog.FAILED, logs.entries.get(0).getStatus());
        assertTrue(deliveries.failed.contains(DedupKeys.failedKey(SUBSCRIPTION_ID.toString(), EventTypes.ISSUE_OPENED, 1423)));
        assertFalse(deliveries.delivered.contains(DedupKeys.deliveredKey(SUBSCRIPTION_ID.toString(), EventTypes.ISSUE_OPENED, 1423)));
    }

    @Test
    void failedDeliveryCheckpointAfterAcceptDoesNotRepost() {
        deliveries.failWrites = true;
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        GitHubEvent event = issue(List.of());

        service.handle(event);
        service.handle(event);

        assertEquals(1, calls.get());
        assertEquals(1, logs.entries.size());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
        assertTrue(dlq.failures.isEmpty());
    }

    @Test
    void failedDeliveryCheckpointDoesNotRepostWhenASiblingIsThrottled() {
        deliveries.failWrites = true;
        UUID paced = UUID.fromString("66666666-6666-6666-6666-666666666666");
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                calls.incrementAndGet();
                if (request.getPath().contains("slow")) {
                    return new MockResponse().setResponseCode(429).setHeader("Retry-After", "60");
                }
                return new MockResponse().setResponseCode(204);
            }
        });
        lookup.targets.add(new DeliveryTarget(
                USER_ID, SUBSCRIPTION_ID, CHANNEL_ID, "discord", true,
                server.url("/ok").toString(), EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(new DeliveryTarget(
                USER_ID, paced, CHANNEL_ID, "discord", true,
                server.url("/slow").toString(), EventTypes.ISSUE_OPENED, List.of()));
        GitHubEvent event = issue(List.of());

        assertThrows(DiscordThrottleException.class, () -> service.handle(event));
        assertThrows(DiscordThrottleException.class, () -> service.handle(event));

        assertEquals(3, calls.get());
        assertEquals(1, logs.entries.size());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
        assertTrue(dlq.failures.isEmpty());
    }

    @Test
    void failedTerminalCheckpointDoesNotReplayTheWebhook() {
        deliveries.failWrites = true;
        nextStatus = 400;
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        GitHubEvent event = issue(List.of());

        service.handle(event);
        service.handle(event);

        assertEquals(1, calls.get());
        assertEquals(1, dlq.failures.size());
        assertEquals(1, logs.entries.size());
        assertEquals(NotificationLog.FAILED, logs.entries.get(0).getStatus());
    }

    @Test
    void secondHandleDoesNotPostAgainAfterDiscordAccepted() {
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));
        GitHubEvent event = issue(List.of());

        service.handle(event);
        service.handle(event);

        assertEquals(1, calls.get());
        assertEquals(1, logs.entries.size());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(0).getStatus());
        assertTrue(dlq.failures.isEmpty());
        assertTrue(deliveries.delivered.contains(
                DedupKeys.deliveredKey(SUBSCRIPTION_ID.toString(), EventTypes.ISSUE_OPENED, 1423)));
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
    void oneInvalidWebhookDoesNotBlockOtherSubscribers() {
        UUID otherSubscription = UUID.fromString("44444444-4444-4444-4444-444444444444");
        lookup.targets.add(new DeliveryTarget(
                USER_ID, SUBSCRIPTION_ID, CHANNEL_ID, "discord", true, "not a url", EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(new DeliveryTarget(
                USER_ID,
                otherSubscription,
                CHANNEL_ID,
                "discord",
                true,
                server.url("/webhook").toString(),
                EventTypes.ISSUE_OPENED,
                List.of()));

        service.handle(issue(List.of()));

        assertEquals(1, calls.get());
        assertEquals(2, logs.entries.size());
        assertEquals(NotificationLog.FAILED, logs.entries.get(0).getStatus());
        assertEquals(SUBSCRIPTION_ID.toString(), logs.entries.get(0).getSubscriptionId());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(1).getStatus());
        assertEquals(otherSubscription.toString(), logs.entries.get(1).getSubscriptionId());
        assertEquals(1, dlq.failures.size());
    }

    @Test
    void thrownDeliveryDoesNotBlockTheNextSubscriber() {
        DiscordClient discord = new DiscordClient(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), mapper, Duration.ofSeconds(2)) {
            @Override
            public DiscordResponse post(String webhookUrl, String content) {
                if (webhookUrl.contains("bad.example")) {
                    throw new IllegalStateException("channel config is not readable");
                }
                return super.post(webhookUrl, content);
            }
        };
        service = new NotificationService(
                lookup, discord, logs, dlq, Clock.fixed(NOW, ZoneOffset.UTC), sleeper, 3, Duration.ofMillis(1), deliveries);
        UUID otherSubscription = UUID.fromString("55555555-5555-5555-5555-555555555555");
        lookup.targets.add(new DeliveryTarget(
                USER_ID,
                SUBSCRIPTION_ID,
                CHANNEL_ID,
                "discord",
                true,
                "https://bad.example/hook",
                EventTypes.ISSUE_OPENED,
                List.of()));
        lookup.targets.add(new DeliveryTarget(
                USER_ID,
                otherSubscription,
                CHANNEL_ID,
                "discord",
                true,
                server.url("/webhook").toString(),
                EventTypes.ISSUE_OPENED,
                List.of()));

        service.handle(issue(List.of()));

        assertEquals(1, calls.get());
        assertEquals(NotificationLog.FAILED, logs.entries.get(0).getStatus());
        assertEquals("channel config is not readable", logs.entries.get(0).getError());
        assertEquals(NotificationLog.SUCCESS, logs.entries.get(1).getStatus());
        assertEquals(otherSubscription.toString(), logs.entries.get(1).getSubscriptionId());
    }

    @Test
    void unrecordedFailureIsNotAcknowledged() {
        logs.failWrites = true;
        lookup.targets.add(new DeliveryTarget(
                USER_ID, SUBSCRIPTION_ID, CHANNEL_ID, "discord", true, "not a url", EventTypes.ISSUE_OPENED, List.of()));
        lookup.targets.add(discord(EventTypes.ISSUE_OPENED, List.of()));

        assertThrows(IllegalStateException.class, () -> service.handle(issue(List.of())));

        assertEquals(1, calls.get());
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
        private boolean failWrites;

        @Override
        public void append(NotificationLog log) {
            if (failWrites && NotificationLog.FAILED.equals(log.getStatus())) {
                throw new IllegalStateException("mongo down");
            }
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
        private final List<Duration> slept = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            calls++;
            slept.add(duration);
        }
    }

    private static final class MemoryDeliveries implements DedupStore {
        private final Set<String> delivered = new HashSet<>();
        private final Set<String> failed = new HashSet<>();
        private boolean failReads;
        private boolean failWrites;

        @Override
        public boolean tryStart(String owner, String repo, String eventType, int number) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCompleted(String owner, String repo, String eventType, int number) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(String owner, String repo, String eventType, int number) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(String owner, String repo, String eventType, int number) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean alreadyDelivered(String subscriptionId, String eventType, int number) {
            if (failReads) {
                throw new IllegalStateException("redis down");
            }
            return delivered.contains(DedupKeys.deliveredKey(subscriptionId, eventType, number));
        }

        @Override
        public void markDelivered(String subscriptionId, String eventType, int number) {
            if (failWrites) {
                throw new IllegalStateException("redis down");
            }
            delivered.add(DedupKeys.deliveredKey(subscriptionId, eventType, number));
        }

        @Override
        public boolean alreadyFailed(String subscriptionId, String eventType, int number) {
            if (failReads) {
                throw new IllegalStateException("redis down");
            }
            return failed.contains(DedupKeys.failedKey(subscriptionId, eventType, number));
        }

        @Override
        public void markFailed(String subscriptionId, String eventType, int number) {
            if (failWrites) {
                throw new IllegalStateException("redis down");
            }
            failed.add(DedupKeys.failedKey(subscriptionId, eventType, number));
        }
    }
}
