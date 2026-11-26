package app.gitwatch.bench;

import app.gitwatch.model.GitHubEvent;
import app.gitwatch.notifier.DiscordClient;
import app.gitwatch.notifier.DiscordMessageFormatter;
import app.gitwatch.poller.EventJson;
import app.gitwatch.poller.GitHubPage;
import app.gitwatch.poller.RemoteItem;
import app.gitwatch.poller.RepoPoller;
import app.gitwatch.service.LabelFilter;
import app.gitwatch.service.PollTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Throughput for the poll and notify CPU paths. HTTP, Kafka, Redis, and sleep are not in these numbers.
 *
 * <p>Run {@code mvn -Pbenchmark test-compile exec:exec}. JMH 1.37, one fork, three 1s warmups, five 1s
 * measurements, gc profiler. The same flags are in the benchmark profile so a rerun matches this class.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class HotPathBenchmark {

    private HotPathFakes.IssuesParser parser;
    private String pageJson;
    private ObjectMapper eventMapper;
    private GitHubEvent event;
    private String eventJson;
    private List<String> requiredLabels;
    private List<String> eventLabels;
    private HotPathFakes.PollOne pollOne;
    private PollTarget pollTarget;
    private HotPathFakes.CountingPublisher publisher;
    private NotificationHarness notify;

    @Setup
    public void setup() {
        pageJson = HotPathFixtures.issuesPageJson();
        parser = new HotPathFakes.IssuesParser();
        event = HotPathFixtures.event();
        eventMapper = EventJson.mapper();
        eventJson = EventJson.write(eventMapper, event);
        requiredLabels = HotPathFixtures.requiredLabels();
        eventLabels = HotPathFixtures.eventLabels();

        GitHubPage issues = new GitHubPage(HotPathFixtures.issueItems(), 5000, HotPathFixtures.NOW, false);
        GitHubPage pulls = new GitHubPage(HotPathFixtures.pullItems(), 5000, HotPathFixtures.NOW, false);
        publisher = new HotPathFakes.CountingPublisher();
        RepoPoller poller = HotPathFakes.poller(
                new HotPathFakes.MemoryGitHub(issues, pulls),
                new HotPathFakes.AlwaysNewDedup(),
                publisher,
                new HotPathFakes.MemoryStore());
        pollOne = new HotPathFakes.PollOne(poller);
        pollTarget = HotPathFixtures.pollTarget();
        notify = NotificationHarness.create();
    }

    @Benchmark
    public List<RemoteItem> parseIssuesPage() {
        return parser.parse(pageJson);
    }

    @Benchmark
    public String eventJsonWrite() {
        return EventJson.write(eventMapper, event);
    }

    @Benchmark
    public GitHubEvent eventJsonRead() {
        return EventJson.read(eventMapper, eventJson);
    }

    @Benchmark
    public String discordFormat() {
        return DiscordMessageFormatter.format(event);
    }

    @Benchmark
    public boolean labelFilter() {
        return LabelFilter.matches(requiredLabels, eventLabels);
    }

    @Benchmark
    public int pollOne() {
        pollOne.run(pollTarget, HotPathFixtures.NOW);
        return publisher.count;
    }

    @Benchmark
    public int notifyHandle() {
        notify.service.handle(event);
        return notify.logs.writes;
    }

    private static final class NotificationHarness {
        private final app.gitwatch.notifier.NotificationService service;
        private final HotPathFakes.CountingLogs logs;

        private NotificationHarness(app.gitwatch.notifier.NotificationService service, HotPathFakes.CountingLogs logs) {
            this.service = service;
            this.logs = logs;
        }

        private static NotificationHarness create() {
            HotPathFakes.CountingLogs logs = new HotPathFakes.CountingLogs();
            DiscordClient discord = HotPathFakes.discord(new HotPathFakes.LocalHttp());
            return new NotificationHarness(
                    HotPathFakes.notifications(
                            new HotPathFakes.FixedLookup(HotPathFixtures.deliveryTargets()),
                            discord,
                            logs,
                            new HotPathFakes.CountingDlq()),
                    logs);
        }
    }
}
