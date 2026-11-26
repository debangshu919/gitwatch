package app.gitwatch.notifier;

import java.time.Duration;

public final class DiscordThrottleException extends RuntimeException {

    private final Duration retryAfter;
    private final Duration remainingBudget;

    public DiscordThrottleException(Duration retryAfter) {
        this(retryAfter, Duration.ZERO);
    }

    public DiscordThrottleException(Duration retryAfter, Duration remainingBudget) {
        super("Discord returned 429");
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
        this.remainingBudget = remainingBudget == null || remainingBudget.isNegative()
                ? Duration.ZERO
                : remainingBudget;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public Duration remainingBudget() {
        return remainingBudget;
    }
}
