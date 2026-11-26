package app.gitwatch.notifier;

import app.gitwatch.config.AppProperties;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.model.NotificationLog;
import app.gitwatch.service.ChannelPolicy;
import app.gitwatch.service.DeliveryTarget;
import app.gitwatch.service.LabelFilter;
import app.gitwatch.service.SubscriptionLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SubscriptionLookup subscriptions;
    private final DiscordClient discord;
    private final NotificationLogStore logs;
    private final DlqPublisher dlq;
    private final Clock clock;
    private final Sleeper sleeper;
    private final int maxAttempts;
    private final Duration backoff;

    public NotificationService(
            SubscriptionLookup subscriptions,
            DiscordClient discord,
            NotificationLogStore logs,
            DlqPublisher dlq,
            Clock clock,
            AppProperties properties) {
        this(subscriptions, discord, logs, dlq, clock, Sleeper.thread(), properties.getNotifier().getMaxAttempts(), properties.getNotifier().getBackoff());
    }

    public NotificationService(
            SubscriptionLookup subscriptions,
            DiscordClient discord,
            NotificationLogStore logs,
            DlqPublisher dlq,
            Clock clock,
            Sleeper sleeper,
            int maxAttempts,
            Duration backoff) {
        this.subscriptions = subscriptions;
        this.discord = discord;
        this.logs = logs;
        this.dlq = dlq;
        this.clock = clock;
        this.sleeper = sleeper;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff == null ? Duration.ofSeconds(1) : backoff;
    }

    public void handle(GitHubEvent event) {
        List<DeliveryTarget> targets = subscriptions.findActive(event.owner(), event.repo(), event.eventType());
        for (DeliveryTarget target : targets) {
            try {
                deliver(target, event);
            } catch (RuntimeException ex) {
                log.warn("delivery failed for subscription {}", target.subscriptionId(), ex);
                try {
                    fail(target, event, ex.getMessage() == null ? "delivery failed" : ex.getMessage());
                } catch (RuntimeException nested) {
                    log.warn("could not record the failure for subscription {}", target.subscriptionId(), nested);
                }
            }
        }
    }

    private void deliver(DeliveryTarget target, GitHubEvent event) {
        if (!event.eventType().equals(target.eventType())) {
            return;
        }
        if (!target.channelActive()) {
            return;
        }
        if (ChannelPolicy.isTelegram(target.channelType())) {
            log.info("skipping telegram channel {}", target.channelId());
            return;
        }
        if (!ChannelPolicy.isDiscord(target.channelType())) {
            return;
        }
        if (!LabelFilter.matches(target.requiredLabels(), event.labels())) {
            return;
        }
        String content = DiscordMessageFormatter.format(event);
        DiscordResponse last = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            last = discord.post(target.webhookUrl(), content);
            if (last.succeeded()) {
                logs.append(logEntry(target, event, NotificationLog.SUCCESS, null));
                return;
            }
            if (last.permanent()) {
                fail(target, event, last.error());
                return;
            }
            if (attempt >= maxAttempts) {
                break;
            }
            sleep(backoff.multipliedBy(attempt));
        }
        fail(target, event, last == null ? "Discord delivery failed" : last.error());
    }

    private void fail(DeliveryTarget target, GitHubEvent event, String error) {
        logs.append(logEntry(target, event, NotificationLog.FAILED, error));
        dlq.publish(new DeliveryFailure(event, target.subscriptionId().toString(), error, clock.instant()));
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

    private void sleep(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
