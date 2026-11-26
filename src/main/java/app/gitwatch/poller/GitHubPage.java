package app.gitwatch.poller;

import java.time.Instant;
import java.util.List;

public record GitHubPage(List<RemoteItem> items, int remaining, Instant resetAt, boolean lowRemaining) {}
