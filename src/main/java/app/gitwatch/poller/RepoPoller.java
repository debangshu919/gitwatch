package app.gitwatch.poller;

import app.gitwatch.config.AppProperties;
import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.service.DedupStore;
import app.gitwatch.service.PollStateStore;
import app.gitwatch.service.PollTarget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gitwatch.poller.enabled", havingValue = "true", matchIfMissing = true)
public class RepoPoller {

    private static final Logger log = LoggerFactory.getLogger(RepoPoller.class);

    private final PollStateStore store;
    private final GitHubClient github;
    private final DedupStore dedup;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration nextPollDelay;
    private final Duration lease;
    private final Duration rateLimitDelay;
    private final int maxPages;
    private final int pageSize;

    public RepoPoller(
            PollStateStore store,
            GitHubClient github,
            DedupStore dedup,
            EventPublisher publisher,
            AppProperties properties,
            Clock clock) {
        this.store = store;
        this.github = github;
        this.dedup = dedup;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = properties.getPoller().getBatchSize();
        this.nextPollDelay = properties.getPoller().getNextPollDelay();
        this.lease = properties.getPoller().getLease();
        this.rateLimitDelay = properties.getGithub().getRateLimitDelay();
        this.maxPages = properties.getPoller().getMaxPages();
        this.pageSize = properties.getPoller().getPageSize();
    }

    @Scheduled(
            fixedDelayString = "${gitwatch.poller.interval-ms:30000}",
            initialDelayString = "${gitwatch.poller.interval-ms:30000}")
    public void poll() {
        Instant now = clock.instant();
        List<PollTarget> due = store.claimDue(now, batchSize, lease);
        for (PollTarget target : due) {
            try {
                pollOne(target, now);
            } catch (RuntimeException ex) {
                log.warn("poll failed for {}/{}", target.owner(), target.repo());
                store.saveCursors(
                        target.owner(),
                        target.repo(),
                        target.lastIssueNumber(),
                        target.lastPrNumber(),
                        target.lastPolledAt(),
                        now.plus(nextPollDelay));
            }
        }
    }

    void pollOne(PollTarget target, Instant now) {
        FetchResult issues;
        try {
            issues = fetch(target.owner(), target.repo(), target.lastIssueNumber(), true);
        } catch (GitHubRateLimitException ex) {
            delay(target, ex.retryAt());
            return;
        }
        if (issues.rateLimited()) {
            publishAll(issues.events());
            Integer issueCursor = issues.advance() ? issues.cursor() : target.lastIssueNumber();
            store.saveCursors(
                    target.owner(),
                    target.repo(),
                    issueCursor,
                    target.lastPrNumber(),
                    now,
                    delayUntil(now, issues));
            return;
        }
        FetchResult pulls;
        try {
            pulls = fetch(target.owner(), target.repo(), target.lastPrNumber(), false);
        } catch (GitHubRateLimitException ex) {
            publishAll(issues.events());
            Integer issueCursor = issues.advance() ? issues.cursor() : target.lastIssueNumber();
            store.saveCursors(
                    target.owner(),
                    target.repo(),
                    issueCursor,
                    target.lastPrNumber(),
                    now,
                    ex.retryAt());
            return;
        }
        publishAll(issues.events());
        publishAll(pulls.events());
        Integer issueCursor = issues.advance() ? issues.cursor() : target.lastIssueNumber();
        Integer prCursor = pulls.advance() ? pulls.cursor() : target.lastPrNumber();
        Instant next = now.plus(nextPollDelay);
        if (issues.lowRemaining() || pulls.lowRemaining() || pulls.rateLimited()) {
            next = latest(delayUntil(now, issues), delayUntil(now, pulls));
        }
        store.saveCursors(target.owner(), target.repo(), issueCursor, prCursor, now, next);
    }

    private void delay(PollTarget target, Instant retryAt) {
        store.saveCursors(
                target.owner(),
                target.repo(),
                target.lastIssueNumber(),
                target.lastPrNumber(),
                target.lastPolledAt(),
                retryAt);
    }

    private Instant delayUntil(Instant now, FetchResult result) {
        if (result.delayUntil() != null) {
            return result.delayUntil();
        }
        return now.plus(rateLimitDelay);
    }

    private Instant latest(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private FetchResult fetch(String owner, String repo, Integer lastSeen, boolean issues) {
        if (lastSeen == null) {
            return baseline(owner, repo, issues);
        }
        int cursor = lastSeen;
        int highest = cursor;
        boolean reached = false;
        boolean rateLimited = false;
        boolean lowRemaining = false;
        Instant delayUntil = null;
        List<GitHubEvent> events = new ArrayList<>();
        int page = 1;
        while (page <= maxPages) {
            GitHubPage result;
            try {
                result = issues ? github.listIssues(owner, repo, page) : github.listPulls(owner, repo, page);
            } catch (GitHubRateLimitException ex) {
                events.sort(Comparator.comparingInt(GitHubEvent::number));
                return new FetchResult(events, cursor, false, true, true, ex.retryAt());
            }
            if (result.lowRemaining()) {
                lowRemaining = true;
                delayUntil = result.resetAt();
            }
            if (result.items().isEmpty()) {
                reached = true;
                break;
            }
            int minOnPage = Integer.MAX_VALUE;
            for (RemoteItem item : result.items()) {
                minOnPage = Math.min(minOnPage, item.number());
                if (issues && item.pullRequest()) {
                    continue;
                }
                highest = Math.max(highest, item.number());
                if (item.number() > cursor) {
                    events.add(toEvent(owner, repo, item, issues));
                }
            }
            if (result.items().size() < pageSize || minOnPage <= cursor) {
                reached = true;
                break;
            }
            if (lowRemaining) {
                break;
            }
            page++;
        }
        events.sort(Comparator.comparingInt(GitHubEvent::number));
        if (reached) {
            return new FetchResult(events, Math.max(cursor, highest), true, rateLimited, lowRemaining, delayUntil);
        }
        return new FetchResult(events, cursor, false, rateLimited, lowRemaining, delayUntil);
    }

    private FetchResult baseline(String owner, String repo, boolean issues) {
        GitHubPage page = issues ? github.listIssues(owner, repo, 1) : github.listPulls(owner, repo, 1);
        int high = 0;
        for (RemoteItem item : page.items()) {
            high = Math.max(high, item.number());
        }
        return new FetchResult(List.of(), high, true, false, page.lowRemaining(), page.lowRemaining() ? page.resetAt() : null);
    }

    private GitHubEvent toEvent(String owner, String repo, RemoteItem item, boolean issue) {
        return new GitHubEvent(
                issue ? EventTypes.ISSUE_OPENED : EventTypes.PR_OPENED,
                owner,
                repo,
                item.number(),
                item.title(),
                item.author(),
                item.labels(),
                item.url(),
                excerpt(item.body()),
                item.createdAt());
    }

    static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }

    private void publishAll(List<GitHubEvent> events) {
        for (GitHubEvent event : events) {
            if (!dedup.claim(event.owner(), event.repo(), event.eventType(), event.number())) {
                continue;
            }
            try {
                publisher.publish(event);
            } catch (RuntimeException ex) {
                dedup.release(event.owner(), event.repo(), event.eventType(), event.number());
                throw ex;
            }
        }
    }

    private record FetchResult(
            List<GitHubEvent> events,
            Integer cursor,
            boolean advance,
            boolean rateLimited,
            boolean lowRemaining,
            Instant delayUntil) {
    }
}
