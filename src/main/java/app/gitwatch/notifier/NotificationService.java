package app.gitwatch.notifier;

import app.gitwatch.config.AppProperties;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.model.NotificationLog;
import app.gitwatch.service.ChannelPolicy;
import app.gitwatch.service.DedupKeys;
import app.gitwatch.service.DedupStore;
import app.gitwatch.service.DeliveryTarget;
import app.gitwatch.service.LabelFilter;
import app.gitwatch.service.SubscriptionLookup;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    // One record's sleeps must not exceed max.poll.interval.ms and force a rebalance.
    // max-poll-records is 1 for the same reason. A catch-up can emit up to max-pages (5)
    // times page-size (100) issues plus the same number of pull requests, and three
    // attempts will not drain that against a webhook limit. Pace this record and leave
    // the offset uncommitted while Discord is still asking for Retry-After.
    public static final Duration MAX_EVENT_BACKOFF = Duration.ofSeconds(20);

    // application.yml sets max.poll.interval.ms to 300000. A single sleep must stay under
    // that minus headroom, or the consumer is removed and the group rebalances. Headroom
    // covers the 10s HTTP timeout and the commit after the sleep.
    static final long MAX_POLL_INTERVAL_MS = 300_000L;
    static final Duration POLL_HEADROOM = Duration.ofSeconds(30);
    public static final Duration MAX_SLEEP = Duration.ofMillis(MAX_POLL_INTERVAL_MS).minus(POLL_HEADROOM);

    private final SubscriptionLookup subscriptions;
    private final DiscordClient discord;
    private final NotificationLogStore logs;
    private final DlqPublisher dlq;
    private final DedupStore deliveries;
    // Same-process guard for a replay before Redis is readable again, not a second 30-day store.
    // Redis keeps the durable record. The cap drops the oldest key so this set cannot grow for the life of the process.
    static final int LOCAL_SETTLED_CAP = 4096;
    private final Map<String, Boolean> settledLocally = newSettledKeys();
    private final Clock clock;
    private final Sleeper sleeper;
    private final int maxAttempts;
    private final Duration backoff;

    @Autowired
    public NotificationService(
            SubscriptionLookup subscriptions,
            DiscordClient discord,
            NotificationLogStore logs,
            DlqPublisher dlq,
            Clock clock,
            DedupStore deliveries,
            AppProperties properties) {
        this(
                subscriptions,
                discord,
                logs,
                dlq,
                clock,
                Sleeper.thread(),
                properties.getNotifier().getMaxAttempts(),
                properties.getNotifier().getBackoff(),
                deliveries);
    }

    public NotificationService(
            SubscriptionLookup subscriptions,
            DiscordClient discord,
            NotificationLogStore logs,
            DlqPublisher dlq,
            Clock clock,
            Sleeper sleeper,
            int maxAttempts,
            Duration backoff,
            DedupStore deliveries) {
        this.subscriptions = subscriptions;
        this.discord = discord;
        this.logs = logs;
        this.dlq = dlq;
        this.deliveries = deliveries;
        this.clock = clock;
        this.sleeper = sleeper;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff == null ? Duration.ofSeconds(1) : backoff;
    }

    public void handle(GitHubEvent event) {
        List<DeliveryTarget> targets = subscriptions.findActive(event.owner(), event.repo(), event.eventType());
        Duration backoffBudget = MAX_EVENT_BACKOFF;
        RuntimeException unrecorded = null;
        DeliveryStoreException storeFailure = null;
        DiscordThrottleException throttled = null;
        for (DeliveryTarget target : targets) {
            try {
                backoffBudget = deliver(target, event, backoffBudget);
            } catch (DiscordThrottleException ex) {
                // The throw skips the assignment, so keep the budget this target already spent.
                backoffBudget = ex.remainingBudget();
                throttled = longer(throttled, ex);
            } catch (DeliveryStoreException ex) {
                storeFailure = ex;
            } catch (RuntimeException ex) {
                log.warn("delivery failed for subscription {}", target.subscriptionId(), ex);
                try {
                    fail(target, event, ex.getMessage() == null ? "delivery failed" : ex.getMessage());
                } catch (DeliveryStoreException nested) {
                    storeFailure = nested;
                } catch (RuntimeException nested) {
                    log.warn("could not record the failure for subscription {}", target.subscriptionId(), nested);
                    unrecorded = nested;
                }
            }
        }
        // A pending throttle must escape even if another target could not be logged.
        // Otherwise the default handler dead-letters the record and the throttle is dropped.
        if (throttled != null) {
            throw withinPollRoom(throttled, backoffBudget);
        }
        if (storeFailure != null) {
            throw storeFailure;
        }
        if (unrecorded != null) {
            throw unrecorded;
        }
    }

    private Duration deliver(DeliveryTarget target, GitHubEvent event, Duration backoffBudget) {
        if (!event.eventType().equals(target.eventType())
                || !target.channelActive()
                || !ChannelPolicy.isDiscord(target.channelType())
                || !LabelFilter.matches(target.requiredLabels(), event.labels())) {
            if (ChannelPolicy.isTelegram(target.channelType())) {
                log.info("skipping telegram channel {}", target.channelId());
            }
            return backoffBudget;
        }
        // A throttle replay must not post again to a webhook Discord already accepted,
        // or to one we already failed for good. A store outage is not a delivery failure.
        if (settled(target, event)) {
            return backoffBudget;
        }
        String content = DiscordMessageFormatter.format(event);
        DiscordResponse last = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            last = discord.post(target.webhookUrl(), content);
            if (last.succeeded()) {
                // Set only after a 2xx. A 429 must not set the key, or the retry would skip the post.
                rememberDelivered(target, event);
                logs.append(logEntry(target, event, NotificationLog.SUCCESS, null));
                return backoffBudget;
            }
            if (last.permanent()) {
                fail(target, event, last.error());
                return backoffBudget;
            }
            if (last.throttled()) {
                Duration delay = retryDelay(last);
                if (attempt >= maxAttempts || delay.compareTo(backoffBudget) > 0) {
                    throw throttle(delay, backoffBudget);
                }
                backoffBudget = sleep(delay, backoffBudget);
                continue;
            }
            if (attempt >= maxAttempts) {
                break;
            }
            backoffBudget = sleep(backoff.multipliedBy(attempt), backoffBudget);
        }
        fail(target, event, last == null ? "Discord delivery failed" : last.error());
        return backoffBudget;
    }

    private Duration retryDelay(DiscordResponse response) {
        Duration delay = response.retryAfter();
        if (delay == null || delay.isNegative()) {
            delay = backoff;
        }
        return capSleep(delay);
    }

    private DiscordThrottleException throttle(Duration retryAfter, Duration budget) {
        Duration remaining = budget == null ? Duration.ZERO : budget;
        Duration spent = MAX_EVENT_BACKOFF.minus(remaining);
        if (spent.isNegative()) {
            spent = Duration.ZERO;
        }
        Duration room = MAX_SLEEP.minus(spent);
        if (room.isNegative()) {
            room = Duration.ZERO;
        }
        Duration delay = capSleep(retryAfter);
        if (delay.compareTo(room) > 0) {
            delay = room;
        }
        return new DiscordThrottleException(delay, remaining);
    }

    private DiscordThrottleException withinPollRoom(DiscordThrottleException throttled, Duration budget) {
        Duration remaining = budget == null ? Duration.ZERO : budget;
        Duration spent = MAX_EVENT_BACKOFF.minus(remaining);
        if (spent.isNegative()) {
            spent = Duration.ZERO;
        }
        Duration room = MAX_SLEEP.minus(spent);
        if (room.isNegative()) {
            room = Duration.ZERO;
        }
        Duration delay = throttled.retryAfter();
        if (delay.compareTo(room) > 0) {
            delay = room;
        }
        return new DiscordThrottleException(delay, remaining);
    }

    private boolean settled(DeliveryTarget target, GitHubEvent event) {
        String subscriptionId = target.subscriptionId().toString();
        String delivered = DedupKeys.deliveredKey(subscriptionId, event.eventType(), event.number());
        String failed = DedupKeys.failedKey(subscriptionId, event.eventType(), event.number());
        if (settledLocally.containsKey(delivered) || settledLocally.containsKey(failed)) {
            return true;
        }
        try {
            return deliveries.alreadyDelivered(subscriptionId, event.eventType(), event.number())
                    || deliveries.alreadyFailed(subscriptionId, event.eventType(), event.number());
        } catch (DeliveryStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new DeliveryStoreException(ex);
        }
    }

    private void rememberDelivered(DeliveryTarget target, GitHubEvent event) {
        String key = DedupKeys.deliveredKey(
                target.subscriptionId().toString(), event.eventType(), event.number());
        // Discord already accepted. Remember here before the Redis write so a replay in this
        // process does not post again when that write fails.
        rememberLocally(key);
        try {
            deliveries.markDelivered(target.subscriptionId().toString(), event.eventType(), event.number());
        } catch (RuntimeException ex) {
            log.warn("could not record delivery for subscription {}", target.subscriptionId(), ex);
        }
    }

    private void rememberFailed(DeliveryTarget target, GitHubEvent event) {
        String key = DedupKeys.failedKey(
                target.subscriptionId().toString(), event.eventType(), event.number());
        rememberLocally(key);
        try {
            deliveries.markFailed(target.subscriptionId().toString(), event.eventType(), event.number());
        } catch (RuntimeException ex) {
            log.warn("could not record a terminal delivery for subscription {}", target.subscriptionId(), ex);
        }
    }

    private void rememberLocally(String key) {
        settledLocally.put(key, Boolean.TRUE);
    }

    static Map<String, Boolean> newSettledKeys() {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > LOCAL_SETTLED_CAP;
            }
        });
    }

    private static DiscordThrottleException longer(DiscordThrottleException current, DiscordThrottleException next) {
        if (current == null || next.retryAfter().compareTo(current.retryAfter()) > 0) {
            return next;
        }
        return current;
    }

    public static Duration capSleep(Duration requested) {
        if (requested == null || requested.isNegative()) {
            return Duration.ZERO;
        }
        return requested.compareTo(MAX_SLEEP) > 0 ? MAX_SLEEP : requested;
    }

    private void fail(DeliveryTarget target, GitHubEvent event, String error) {
        logs.append(logEntry(target, event, NotificationLog.FAILED, error));
        dlq.publish(new DeliveryFailure(event, target.subscriptionId().toString(), error, clock.instant()));
        // Checkpoint only after the failure is recorded. A throttle replay then skips this
        // webhook. This is not the delivered key: that is only set after a 2xx.
        rememberFailed(target, event);
    }

    private NotificationLog logEntry(DeliveryTarget target, GitHubEvent event, String status, String error) {
        NotificationLog entry = new NotificationLog();
        entry.setId(UUID.randomUUID().toString());
        entry.setUserId(target.userId().toString());
        entry.setSubscriptionId(target.subscriptionId().toString());
        entry.setEventType(event.eventType());
        entry.setGithubUrl(event.url());
        entry.setTitle(event.title());
        entry.setStatus(status);
        entry.setError(error);
        entry.setCreatedAt(clock.instant());
        return entry;
    }

    private Duration sleep(Duration requested, Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            return Duration.ZERO;
        }
        Duration step = capSleep(requested);
        if (step.compareTo(budget) > 0) {
            step = budget;
        }
        try {
            sleeper.sleep(step);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return budget.minus(step);
    }
}
