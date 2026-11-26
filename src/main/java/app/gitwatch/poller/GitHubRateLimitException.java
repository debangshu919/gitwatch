package app.gitwatch.poller;

import java.time.Instant;

public class GitHubRateLimitException extends RuntimeException {

    private final Instant retryAt;

    public GitHubRateLimitException(Instant retryAt) {
        super("GitHub rate limit");
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
