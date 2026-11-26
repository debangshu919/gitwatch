package app.gitwatch.model;

public final class EventTypes {

    public static final String ISSUE_OPENED = "issue.opened";
    public static final String PR_OPENED = "pr.opened";

    private EventTypes() {}

    public static boolean isIssue(String eventType) {
        return ISSUE_OPENED.equals(eventType);
    }

    public static boolean isPullRequest(String eventType) {
        return PR_OPENED.equals(eventType);
    }
}
