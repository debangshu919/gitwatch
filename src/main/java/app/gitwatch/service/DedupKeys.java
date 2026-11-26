package app.gitwatch.service;

public final class DedupKeys {

    public static final java.time.Duration TTL = java.time.Duration.ofDays(30);

    private DedupKeys() {
    }

    public static String key(String owner, String repo, String eventType, int number) {
        return "dedup:" + owner + "/" + repo + ":" + eventType + ":" + number;
    }
}
