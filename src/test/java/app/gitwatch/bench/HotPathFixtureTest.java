package app.gitwatch.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.gitwatch.model.EventTypes;
import app.gitwatch.notifier.DiscordClient;
import app.gitwatch.poller.GitHubPage;
import app.gitwatch.poller.RemoteItem;
import app.gitwatch.poller.RepoPoller;
import app.gitwatch.service.LabelFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HotPathFixtureTest {

    @Test
    void issuesPageIsProductionSizedAndParses() {
        String json = HotPathFixtures.issuesPageJson();
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length >= 100_000, "page must be a real 100-item payload");
        List<RemoteItem> items = new HotPathFakes.IssuesParser().parse(json);
        assertEquals(HotPathFixtures.PAGE_SIZE, items.size());
        int pulls = 0;
        for (RemoteItem item : items) {
            assertFalse(item.title().isBlank());
            assertFalse(item.author().isBlank());
            assertFalse(item.url().isBlank());
            assertTrue(item.body().length() > 200);
            assertEquals(4, item.labels().size());
            assertNotNull(item.createdAt());
            if (item.pullRequest()) {
                pulls++;
                assertTrue(item.url().contains("/pull/"));
            }
        }
        assertTrue(pulls >= 10, "page mixes pull_request objects in with issues");
        assertTrue(pulls < items.size());
    }

    @Test
    void pollOneScansOnePageAndSkipsItemsAtOrBelowTheCursor() {
        GitHubPage issues = new GitHubPage(HotPathFixtures.issueItems(), 5000, HotPathFixtures.NOW, false);
        GitHubPage pulls = new GitHubPage(HotPathFixtures.pullItems(), 5000, HotPathFixtures.NOW, false);
        HotPathFakes.CountingPublisher publisher = new HotPathFakes.CountingPublisher();
        HotPathFakes.MemoryStore store = new HotPathFakes.MemoryStore();
        RepoPoller poller = HotPathFakes.poller(
                new HotPathFakes.MemoryGitHub(issues, pulls),
                new HotPathFakes.AlwaysNewDedup(),
                publisher,
                store);
        new HotPathFakes.PollOne(poller).run(HotPathFixtures.pollTarget(), HotPathFixtures.NOW);

        assertTrue(publisher.count > 0);
        assertTrue(publisher.count < HotPathFixtures.PAGE_SIZE);
        assertNotNull(store.saved);
        assertEquals(1, store.saved.issuePage());
        assertTrue(publisher.last.number() > HotPathFixtures.ISSUE_CURSOR || publisher.last.number() > HotPathFixtures.PR_CURSOR);
    }

    @Test
    void handleFiltersFormatsAndPostsWithoutSleeping() {
        HotPathFakes.LocalHttp http = new HotPathFakes.LocalHttp();
        HotPathFakes.CountingLogs logs = new HotPathFakes.CountingLogs();
        HotPathFakes.CountingDlq dlq = new HotPathFakes.CountingDlq();
        DiscordClient discord = HotPathFakes.discord(http);
        var service = HotPathFakes.notifications(
                new HotPathFakes.FixedLookup(HotPathFixtures.deliveryTargets()),
                discord,
                logs,
                dlq);
        assertTrue(LabelFilter.matches(HotPathFixtures.requiredLabels(), HotPathFixtures.eventLabels()));
        service.handle(HotPathFixtures.event());
        assertEquals(3, http.sends);
        assertEquals(3, logs.writes);
        assertEquals(0, dlq.writes);
        assertEquals(EventTypes.ISSUE_OPENED, logs.last.getEventType());
        assertNotNull(logs.last.getCreatedAt());
        assertEquals(Instant.parse("2026-11-26T12:00:00Z"), logs.last.getCreatedAt());
    }
}
