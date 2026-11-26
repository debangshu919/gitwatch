package app.gitwatch.notifier;

import java.time.Duration;

public record DiscordResponse(Kind kind, String error, Duration retryAfter) {

    public enum Kind {
        OK,
        PERMANENT,
        TRANSIENT
    }

    public static DiscordResponse success() {
        return new DiscordResponse(Kind.OK, null, null);
    }

    public static DiscordResponse permanent(String error) {
        return new DiscordResponse(Kind.PERMANENT, error, null);
    }

    public static DiscordResponse transientFailure(String error) {
        return new DiscordResponse(Kind.TRANSIENT, error, null);
    }

    public static DiscordResponse throttled(Duration retryAfter) {
        return new DiscordResponse(Kind.TRANSIENT, "Discord returned 429", retryAfter);
    }

    public boolean succeeded() {
        return kind == Kind.OK;
    }

    public boolean permanent() {
        return kind == Kind.PERMANENT;
    }

    public boolean throttled() {
        return kind == Kind.TRANSIENT && retryAfter != null;
    }
}
