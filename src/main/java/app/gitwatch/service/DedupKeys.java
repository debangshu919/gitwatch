package app.gitwatch.service;

public final class DedupKeys {

    public static final java.time.Duration TTL = java.time.Duration.ofDays(30);
    public static final java.time.Duration IN_FLIGHT = java.time.Duration.ofMinutes(2);
    public static final String IN_FLIGHT_VALUE = "inflight";
    public static final String COMPLETED_VALUE = "done";

    private DedupKeys() {
    }

    public static String key(String owner, String repo, String eventType, int number) {
        return "dedup:" + owner + "/" + repo + ":" + eventType + ":" + number;
    }
}
