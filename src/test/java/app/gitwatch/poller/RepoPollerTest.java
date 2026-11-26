package app.gitwatch.poller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.gitwatch.config.AppProperties;
import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.service.DedupKeys;
import app.gitwatch.service.DedupStore;
import app.gitwatch.service.PollStateStore;
import app.gitwatch.service.PollTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepoPollerTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private MockWebServer server;
    private MemoryPollStateStore store;
    private MemoryDedupStore dedup;
    private RecordingPublisher publisher;
    private RepoPoller poller;
    private final ObjectMapper mapper = EventJson.mapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new MemoryPollStateStore();
        dedup = new MemoryDedupStore();
        publisher = new RecordingPublisher();
        AppProperties properties = new AppProperties();
        properties.getGithub().setApiBase(server.url("/").toString());
        properties.getGithub().setToken("");
        properties.getGithub().setMinRemaining(10);
        properties.getGithub().setRateLimitDelay(Duration.ofMinutes(15));
        properties.getNotifier().setTimeout(Duration.ofSeconds(2));
        properties.getPoller().setPageSize(100);
        properties.getPoller().setMaxPages(3);
        properties.getPoller().setBatchSize(10);
        properties.getPoller().setNextPollDelay(Duration.ofMinutes(3));
        properties.getPoller().setLease(Duration.ofMinutes(5));
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        GitHubClient github = new GitHubClient(http, mapper, Clock.fixed(NOW, ZoneOffset.UTC), properties);
        poller = new RepoPoller(store, github, dedup, publisher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void newIssueIsPublishedOnce() throws Exception {
        store.put(due(1420, 1420));
        enqueueIssues(issue(1423, "Add support for streaming queries", "username", "First 200 characters...", "good first issue"));
        enqueuePulls("[]");

        poller.poll();

        assertEquals(1, publisher.events.size());
        GitHubEvent event = publisher.events.get(0);
        assertEquals(EventTypes.ISSUE_OPENED, event.eventType());
        JsonNode actual = mapper.readTree(EventJson.write(mapper, event));
        JsonNode expected = mapper.readTree("""
                {
                  "event_type": "issue.opened",
                  "owner": "apache",
                  "repo": "iggy",
                  "number": 1423,
                  "title": "Add support for streaming queries",
                  "author": "username",
                  "labels": ["good first issue"],
                  "url": "https://github.com/apache/iggy/issues/1423",
                  "body_excerpt": "First 200 characters...",
                  "created_at": "2026-11-17T03:00:00Z"
                }
                """);
        assertEquals(expected, actual);
        assertTrue(dedup.claimed(DedupKeys.key("apache", "iggy", EventTypes.ISSUE_OPENED, 1423)));
        assertEquals(1423, store.get("apache", "iggy").lastIssueNumber);
        assertEquals(NOW.plus(Duration.ofMinutes(3)), store.get("apache", "iggy").nextPollAt);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void sameNumberIsNotPublishedAgain() throws Exception {
        store.put(due(1420, 1420));
        enqueueIssues(issue(1423, "Add support for streaming queries", "username", "body", "good first issue"));
        enqueuePulls("[]");
        poller.poll();
        assertEquals(1, publisher.events.size());

        store.get("apache", "iggy").lastIssueNumber = 1420;
        store.get("apache", "iggy").nextPollAt = NOW;
        enqueueIssues(issue(1423, "Add support for streaming queries", "username", "body", "good first issue"));
        enqueuePulls("[]");
        poller.poll();

        assertEquals(1, publisher.events.size());
        assertEquals(1423, store.get("apache", "iggy").lastIssueNumber);
    }

    @Test
    void advancedCursorDoesNotPublishTheSameNumber() throws Exception {
        store.put(due(1423, 1423));
        enqueueIssues(issue(1423, "Add support for streaming queries", "username", "body", "bug"));
        enqueuePulls(pull(1423, "old pr"));
        poller.poll();
        assertEquals(0, publisher.events.size());
    }

    @Test
    void pullRequestOnTheIssuesEndpointIsNotEmittedAsAnIssue() throws Exception {
        store.put(due(1400, 1400));
        enqueueIssues(
                issue(1423, "Add support for streaming queries", "username", "body", "good first issue"),
                pullOnIssues(1422, "A pull request"));
        enqueuePulls(pull(1422, "A pull request"));

        poller.poll();

        assertEquals(2, publisher.events.size());
        assertTrue(publisher.events.stream().noneMatch(event -> event.number() == 1422 && EventTypes.ISSUE_OPENED.equals(event.eventType())));
        assertTrue(publisher.events.stream().anyMatch(event -> event.number() == 1423 && EventTypes.ISSUE_OPENED.equals(event.eventType())));
        GitHubEvent pr = publisher.events.stream().filter(event -> EventTypes.PR_OPENED.equals(event.eventType())).findFirst().orElseThrow();
        assertEquals(1422, pr.number());
        assertEquals("https://github.com/apache/iggy/pull/1422", pr.url());
    }

    @Test
    void rateLimitDelaysWithoutPublishingOrAdvancing() throws Exception {
        store.put(due(1420, 1420));
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "120")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"rate limit\"}"));

        poller.poll();

        assertEquals(0, publisher.events.size());
        assertEquals(1420, store.get("apache", "iggy").lastIssueNumber);
        assertEquals(1420, store.get("apache", "iggy").lastPrNumber);
        assertEquals(NOW.plusSeconds(120), store.get("apache", "iggy").nextPollAt);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void lowRemainingDelaysTheNextPoll() throws Exception {
        store.put(due(1420, 1420));
        long reset = NOW.plus(Duration.ofMinutes(15)).getEpochSecond();
        enqueueIssues(issue(1423, "title", "username", "body", "bug"), Map.of(
                "X-RateLimit-Remaining", "1",
                "X-RateLimit-Reset", Long.toString(reset)));
        enqueuePulls("[]", Map.of(
                "X-RateLimit-Remaining", "1",
                "X-RateLimit-Reset", Long.toString(reset)));

        poller.poll();

        assertEquals(1, publisher.events.size());
        assertEquals(Instant.ofEpochSecond(reset), store.get("apache", "iggy").nextPollAt);
        assertFalse(store.get("apache", "iggy").nextPollAt.equals(NOW.plus(Duration.ofMinutes(3))));
    }

    @Test
    void nullCursorBaselinesWithoutPublishing() throws Exception {
        store.put(new PollTarget("apache", "iggy", null, null, null, NOW));
        enqueueIssues(issue(50, "old", "username", "body"));
        enqueuePulls(pull(51, "old pr"));

        poller.poll();

        assertEquals(0, publisher.events.size());
        assertEquals(50, store.get("apache", "iggy").lastIssueNumber);
        assertEquals(51, store.get("apache", "iggy").lastPrNumber);
    }

    @Test
    void backlogLargerThanMaxPagesIsPublishedOnLaterPolls() throws Exception {
        poller = poller(2, 1);
        store.put(due(10, 10));
        int[][] pages = {{16, 15}, {14, 13}, {12, 11}, {10, 9}};

        for (int i = 0; i < pages.length; i++) {
            enqueueIssues(issue(pages[i][0], "title", "username", "body"), issue(pages[i][1], "title", "username", "body"));
            enqueuePulls("[]");
            assertEquals(i + 1, pollIssuesPage());
            Mutable state = store.get("apache", "iggy");
            if (i < pages.length - 1) {
                assertEquals(10, state.lastIssueNumber);
                assertEquals(i + 2, state.issuePage);
                assertEquals(16, state.issueHighWater);
            } else {
                assertEquals(16, state.lastIssueNumber);
                assertEquals(1, state.issuePage);
                assertNull(state.issueHighWater);
            }
        }

        assertEquals(List.of(15, 16, 13, 14, 11, 12), publisher.events.stream().map(GitHubEvent::number).toList());

        enqueueIssues(issue(17, "title", "username", "body"), issue(16, "title", "username", "body"));
        enqueuePulls("[]");
        assertEquals(1, pollIssuesPage());
        assertEquals(17, store.get("apache", "iggy").lastIssueNumber);
        assertEquals(List.of(15, 16, 13, 14, 11, 12, 17), publisher.events.stream().map(GitHubEvent::number).toList());
    }

    @Test
    void publishFailureReleasesInFlightOwnershipAndDoesNotAdvance() throws Exception {
        store.put(due(1420, 1420));
        enqueueIssues(issue(1423, "title", "username", "body", "bug"));
        enqueuePulls("[]");
        publisher.failNext = true;

        poller.poll();

        assertEquals(1420, store.get("apache", "iggy").lastIssueNumber);
        assertFalse(dedup.claimed(DedupKeys.key("apache", "iggy", EventTypes.ISSUE_OPENED, 1423)));
        enqueueIssues(issue(1423, "title", "username", "body", "bug"));
        enqueuePulls("[]");
        store.get("apache", "iggy").nextPollAt = NOW;
        poller.poll();
        assertEquals(1, publisher.events.size());
        assertEquals(1423, store.get("apache", "iggy").lastIssueNumber);
    }

    @Test
    void bodyExcerptIsTruncatedTo200Characters() {
        String body = "x".repeat(250);
        assertEquals(200, RepoPoller.excerpt(body).length());
        assertEquals(body.substring(0, 200), RepoPoller.excerpt(body));
        assertEquals("", RepoPoller.excerpt(null));
    }

    private void enqueueIssues(String... items) {
        enqueueIssues(String.join(",", items), Map.of("X-RateLimit-Remaining", "4000"));
    }

    private void enqueueIssues(String body, Map<String, String> headers) {
        server.enqueue(json("[" + body + "]", headers));
    }

    private void enqueuePulls(String body) {
        enqueuePulls(body, Map.of("X-RateLimit-Remaining", "4000"));
    }

    private void enqueuePulls(String body, Map<String, String> headers) {
        String payload = body.startsWith("[") ? body : "[" + body + "]";
        server.enqueue(json(payload, headers));
    }

    private MockResponse json(String body, Map<String, String> headers) {
        MockResponse response = new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
        headers.forEach(response::setHeader);
        return response;
    }

    private static String issue(int number, String title, String author, String body, String... labels) {
        String labelJson = labels.length == 0 ? "[]" : "[{" + "\"name\":\"" + String.join("\"},{\"name\":\"", labels) + "\"}]";
        return """
                {"number":%d,"title":"%s","user":{"login":"%s"},"labels":%s,"html_url":"https://github.com/apache/iggy/issues/%d","body":"%s","created_at":"2026-11-17T03:00:00Z"}
                """.formatted(number, title, author, labelJson, number, body).trim();
    }

    private static String pull(int number, String title) {
        return """
                {"number":%d,"title":"%s","user":{"login":"someone"},"labels":[],"html_url":"https://github.com/apache/iggy/pull/%d","body":"pr","created_at":"2026-11-17T02:00:00Z"}
                """.formatted(number, title, number).trim();
    }

    private static String pullOnIssues(int number, String title) {
        return """
                {"number":%d,"title":"%s","user":{"login":"someone"},"labels":[],"html_url":"https://github.com/apache/iggy/pull/%d","body":"pr","created_at":"2026-11-17T02:00:00Z","pull_request":{"url":"https://api.github.com/repos/apache/iggy/pulls/%d"}}
                """.formatted(number, title, number, number).trim();
    }

    private static PollTarget due(int issueNumber, int prNumber) {
        return new PollTarget("apache", "iggy", issueNumber, prNumber, null, NOW);
    }

    private RepoPoller poller(int pageSize, int maxPages) {
        AppProperties properties = new AppProperties();
        properties.getGithub().setApiBase(server.url("/").toString());
        properties.getGithub().setToken("");
        properties.getGithub().setMinRemaining(10);
        properties.getGithub().setRateLimitDelay(Duration.ofMinutes(15));
        properties.getNotifier().setTimeout(Duration.ofSeconds(2));
        properties.getPoller().setPageSize(pageSize);
        properties.getPoller().setMaxPages(maxPages);
        properties.getPoller().setBatchSize(10);
        properties.getPoller().setNextPollDelay(Duration.ofMinutes(3));
        properties.getPoller().setLease(Duration.ofMinutes(5));
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        GitHubClient github = new GitHubClient(http, mapper, Clock.fixed(NOW, ZoneOffset.UTC), properties);
        return new RepoPoller(store, github, dedup, publisher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private int pollIssuesPage() throws Exception {
        poller.poll();
        RecordedRequest issues = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest pulls = server.takeRequest(2, TimeUnit.SECONDS);
        assertTrue(issues != null && issues.getPath().contains("/issues?"));
        assertTrue(pulls != null && pulls.getPath().contains("/pulls?"));
        assertEquals(1, queryPage(pulls.getPath()));
        store.get("apache", "iggy").nextPollAt = NOW;
        return queryPage(issues.getPath());
    }

    private static int queryPage(String path) {
        String key = "&page=";
        int start = path.indexOf(key);
        assertTrue(start >= 0, path);
        start += key.length();
        int end = path.indexOf('&', start);
        String value = end < 0 ? path.substring(start) : path.substring(start, end);
        return Integer.parseInt(value);
    }

    private static final class MemoryPollStateStore implements PollStateStore {
        private final Map<String, Mutable> states = new LinkedHashMap<>();

        void put(PollTarget target) {
            states.put(target.owner() + "/" + target.repo(), Mutable.from(target));
        }

        Mutable get(String owner, String repo) {
            return states.get(owner + "/" + repo);
        }

        @Override
        public List<PollTarget> claimDue(Instant now, int limit, Duration lease) {
            List<PollTarget> due = new ArrayList<>();
            for (Mutable state : states.values()) {
                if (state.nextPollAt == null || !state.nextPollAt.isAfter(now)) {
                    due.add(state.snapshot());
                    state.nextPollAt = now.plus(lease);
                    if (due.size() == limit) {
                        break;
                    }
                }
            }
            return due;
        }

        @Override
        public void save(PollTarget target) {
            Mutable state = states.get(target.owner() + "/" + target.repo());
            state.lastIssueNumber = target.lastIssueNumber();
            state.lastPrNumber = target.lastPrNumber();
            state.issuePage = target.issuePage();
            state.prPage = target.prPage();
            state.issueHighWater = target.issueHighWater();
            state.prHighWater = target.prHighWater();
            state.lastPolledAt = target.lastPolledAt();
            state.nextPollAt = target.nextPollAt();
        }
    }

    private static final class Mutable {
        String owner;
        String repo;
        Integer lastIssueNumber;
        Integer lastPrNumber;
        int issuePage = 1;
        int prPage = 1;
        Integer issueHighWater;
        Integer prHighWater;
        Instant lastPolledAt;
        Instant nextPollAt;

        static Mutable from(PollTarget target) {
            Mutable state = new Mutable();
            state.owner = target.owner();
            state.repo = target.repo();
            state.lastIssueNumber = target.lastIssueNumber();
            state.lastPrNumber = target.lastPrNumber();
            state.issuePage = target.issuePage();
            state.prPage = target.prPage();
            state.issueHighWater = target.issueHighWater();
            state.prHighWater = target.prHighWater();
            state.lastPolledAt = target.lastPolledAt();
            state.nextPollAt = target.nextPollAt();
            return state;
        }

        PollTarget snapshot() {
            return new PollTarget(
                    owner,
                    repo,
                    lastIssueNumber,
                    lastPrNumber,
                    issuePage,
                    prPage,
                    issueHighWater,
                    prHighWater,
                    lastPolledAt,
                    nextPollAt);
        }
    }

    private static final class MemoryDedupStore implements DedupStore {
        private final Map<String, String> keys = new LinkedHashMap<>();

        @Override
        public boolean tryStart(String owner, String repo, String eventType, int number) {
            String key = DedupKeys.key(owner, repo, eventType, number);
            if (keys.containsKey(key)) {
                return false;
            }
            keys.put(key, DedupKeys.IN_FLIGHT_VALUE);
            return true;
        }

        @Override
        public boolean isCompleted(String owner, String repo, String eventType, int number) {
            return DedupKeys.COMPLETED_VALUE.equals(keys.get(DedupKeys.key(owner, repo, eventType, number)));
        }

        @Override
        public void complete(String owner, String repo, String eventType, int number) {
            keys.put(DedupKeys.key(owner, repo, eventType, number), DedupKeys.COMPLETED_VALUE);
        }

        @Override
        public void release(String owner, String repo, String eventType, int number) {
            String key = DedupKeys.key(owner, repo, eventType, number);
            if (DedupKeys.IN_FLIGHT_VALUE.equals(keys.get(key))) {
                keys.remove(key);
            }
        }

        boolean claimed(String key) {
            return DedupKeys.COMPLETED_VALUE.equals(keys.get(key));
        }
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<GitHubEvent> events = new ArrayList<>();
        private boolean failNext;

        @Override
        public void publish(GitHubEvent event) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("kafka down");
            }
            events.add(event);
        }
    }
}
