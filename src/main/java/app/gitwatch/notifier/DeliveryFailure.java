package app.gitwatch.notifier;

import app.gitwatch.model.GitHubEvent;
import java.time.Instant;

public record DeliveryFailure(GitHubEvent event, String subscriptionId, String error, Instant failedAt) {}
