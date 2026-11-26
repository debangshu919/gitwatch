package app.gitwatch.notifier;

import java.time.Duration;

public final class DiscordThrottleException extends RuntimeException {

    private final Duration retryAfter;

    public DiscordThrottleException(Duration retryAfter) {
        super("Discord returned 429");
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
