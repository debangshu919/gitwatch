package app.gitwatch.notifier;

public record DiscordResponse(Kind kind, String error) {

    public enum Kind {
        OK,
        PERMANENT,
        TRANSIENT
    }

    public static DiscordResponse success() {
        return new DiscordResponse(Kind.OK, null);
    }

    public static DiscordResponse permanent(String error) {
        return new DiscordResponse(Kind.PERMANENT, error);
    }

    public static DiscordResponse transientFailure(String error) {
        return new DiscordResponse(Kind.TRANSIENT, error);
    }

    public boolean succeeded() {
        return kind == Kind.OK;
    }

    public boolean permanent() {
        return kind == Kind.PERMANENT;
    }
}
