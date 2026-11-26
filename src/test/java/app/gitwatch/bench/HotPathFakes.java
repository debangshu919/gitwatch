package app.gitwatch.bench;

import app.gitwatch.config.AppProperties;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.model.NotificationLog;
import app.gitwatch.notifier.DeliveryFailure;
import app.gitwatch.notifier.DiscordClient;
import app.gitwatch.notifier.DlqPublisher;
import app.gitwatch.notifier.NotificationLogStore;
import app.gitwatch.notifier.NotificationService;
import app.gitwatch.poller.EventPublisher;
import app.gitwatch.poller.GitHubClient;
import app.gitwatch.poller.GitHubPage;
import app.gitwatch.poller.RepoPoller;
import app.gitwatch.service.DedupStore;
import app.gitwatch.service.DeliveryTarget;
import app.gitwatch.service.PollStateStore;
import app.gitwatch.service.PollTarget;
import app.gitwatch.service.SubscriptionLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * In-memory stand-ins so the poll and notify benchmarks measure CPU work, not sockets.
 */
public final class HotPathFakes {

    private HotPathFakes() {
    }

    public static RepoPoller poller(GitHubClient github, DedupStore dedup, EventPublisher publisher, PollStateStore store) {
        AppProperties properties = new AppProperties();
        properties.getPoller().setPageSize(HotPathFixtures.PAGE_SIZE);
        properties.getPoller().setMaxPages(5);
        properties.getPoller().setBatchSize(10);
        properties.getPoller().setNextPollDelay(Duration.ofMinutes(3));
        properties.getPoller().setLease(Duration.ofMinutes(5));
        properties.getGithub().setMinRemaining(10);
        properties.getGithub().setRateLimitDelay(Duration.ofMinutes(15));
        return new RepoPoller(
                store,
                github,
                dedup,
                publisher,
                properties,
                Clock.fixed(HotPathFixtures.NOW, ZoneOffset.UTC));
    }

    public static NotificationService notifications(
            SubscriptionLookup lookup,
            DiscordClient discord,
            NotificationLogStore logs,
            DlqPublisher dlq) {
        return new NotificationService(
                lookup,
                discord,
                logs,
                dlq,
                Clock.fixed(HotPathFixtures.NOW, ZoneOffset.UTC),
                duration -> {
                },
                3,
                Duration.ofSeconds(2));
    }

    public static DiscordClient discord(HttpClient http) {
        return new DiscordClient(http, new ObjectMapper(), Duration.ofSeconds(10));
    }

    public static final class IssuesParser {
        private final GitHubClient client;
        private final MethodHandle parse;

        public IssuesParser() {
            try {
                client = new GitHubClient(
                        null,
                        new ObjectMapper(),
                        Clock.systemUTC(),
                        "https://api.github.com",
                        "",
                        10,
                        Duration.ofMinutes(15),
                        HotPathFixtures.PAGE_SIZE,
                        Duration.ofSeconds(10));
                Method method = GitHubClient.class.getDeclaredMethod("parse", String.class, String.class);
                method.setAccessible(true);
                parse = MethodHandles.lookup().unreflect(method);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        public List<app.gitwatch.poller.RemoteItem> parse(String body) {
            return parse(body, "issues");
        }

        @SuppressWarnings("unchecked")
        public List<app.gitwatch.poller.RemoteItem> parse(String body, String kind) {
            try {
                return (List<app.gitwatch.poller.RemoteItem>) parse.invoke(client, body, kind);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Throwable ex) {
                Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException wrapped && wrapped.getCause() != null
                        ? wrapped.getCause()
                        : ex;
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            }
        }
    }

    public static final class PollOne {
        private final RepoPoller poller;
        private final MethodHandle pollOne;

        public PollOne(RepoPoller poller) {
            this.poller = poller;
            try {
                Method method = RepoPoller.class.getDeclaredMethod("pollOne", PollTarget.class, Instant.class);
                method.setAccessible(true);
                pollOne = MethodHandles.lookup().unreflect(method);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        public void run(PollTarget target, Instant now) {
            try {
                pollOne.invoke(poller, target, now);
            } catch (Throwable ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    public static final class MemoryGitHub extends GitHubClient {
        private final GitHubPage issues;
        private final GitHubPage pulls;

        public MemoryGitHub(GitHubPage issues, GitHubPage pulls) {
            super(
                    null,
                    new ObjectMapper(),
                    Clock.systemUTC(),
                    "https://api.github.com",
                    "",
                    10,
                    Duration.ofMinutes(15),
                    HotPathFixtures.PAGE_SIZE,
                    Duration.ofSeconds(10));
            this.issues = issues;
            this.pulls = pulls;
        }

        @Override
        public GitHubPage listIssues(String owner, String repo, int page) {
            return issues;
        }

        @Override
        public GitHubPage listPulls(String owner, String repo, int page) {
            return pulls;
        }
    }

    public static final class AlwaysNewDedup implements DedupStore {
        @Override
        public boolean tryStart(String owner, String repo, String eventType, int number) {
            return true;
        }

        @Override
        public boolean isCompleted(String owner, String repo, String eventType, int number) {
            return false;
        }

        @Override
        public void complete(String owner, String repo, String eventType, int number) {
        }

        @Override
        public void release(String owner, String repo, String eventType, int number) {
        }
    }

    public static final class MemoryStore implements PollStateStore {
        public PollTarget saved;

        @Override
        public List<PollTarget> claimDue(Instant now, int limit, Duration lease) {
            return List.of();
        }

        @Override
        public void save(PollTarget target) {
            saved = target;
        }
    }

    public static final class CountingPublisher implements EventPublisher {
        public int count;
        public GitHubEvent last;

        @Override
        public void publish(GitHubEvent event) {
            count++;
            last = event;
        }
    }

    public static final class FixedLookup implements SubscriptionLookup {
        private final List<DeliveryTarget> targets;

        public FixedLookup(List<DeliveryTarget> targets) {
            this.targets = targets;
        }

        @Override
        public List<DeliveryTarget> findActive(String owner, String repo, String eventType) {
            return targets;
        }
    }

    public static final class CountingLogs implements NotificationLogStore {
        public int writes;
        public NotificationLog last;

        @Override
        public void append(NotificationLog log) {
            writes++;
            last = log;
        }
    }

    public static final class CountingDlq implements DlqPublisher {
        public int writes;

        @Override
        public void publish(DeliveryFailure failure) {
            writes++;
        }
    }

    public static final class LocalHttp extends HttpClient {
        public int sends;
        private final HttpResponse<String> ok = new StatusResponse(204);

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            sends++;
            return (HttpResponse<T>) ok;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StatusResponse implements HttpResponse<String> {
        private final int status;

        private StatusResponse(int status) {
            this.status = status;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return "";
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
