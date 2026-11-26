package app.gitwatch.bench;

import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import app.gitwatch.poller.RemoteItem;
import app.gitwatch.service.DeliveryTarget;
import app.gitwatch.service.PollTarget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Production-sized fixtures for the poll and notify benchmarks.
 * application.yml sets page-size 100 and max-pages 5. The steady-state poll reads one page and
 * stops because the oldest number on that page is at or below the cursor.
 */
public final class HotPathFixtures {

    public static final int PAGE_SIZE = 100;
    public static final int ISSUE_HIGH = 1500;
    public static final int ISSUE_CURSOR = 1470;
    public static final int PR_HIGH = 1600;
    public static final int PR_CURSOR = 1570;
    public static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    public static final String OWNER = "apache";
    public static final String REPO = "iggy";

    private static final String[] LABEL_NAMES = {
        "bug",
        "enhancement",
        "good first issue",
        "help wanted",
        "question",
        "documentation",
        "duplicate",
        "wontfix"
    };

    private static final String[] LABEL_COLORS = {
        "d73a4a",
        "a2eeef",
        "7057ff",
        "008672",
        "d876e3",
        "0075ca",
        "cfd3d7",
        "ffffff"
    };

    private static final String BODY_STEM =
            "Polling this repository failed to advance the cursor after a page that mixes issues and pull requests. "
                    + "The notifier should still format the title, author, and labels, and skip Discord when a required label is missing. "
                    + "Bodies on a real issues page are long enough that Jackson has to walk past user, label, and reaction objects. ";

    private HotPathFixtures() {
    }

    public static String issuesPageJson() {
        StringBuilder json = new StringBuilder(400_000);
        json.append('[');
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i > 0) {
                json.append(',');
            }
            appendItem(json, ISSUE_HIGH - i, (ISSUE_HIGH - i) % 7 == 0);
        }
        json.append(']');
        return json.toString();
    }

    public static List<RemoteItem> issueItems() {
        return items(ISSUE_HIGH, PAGE_SIZE, false);
    }

    public static List<RemoteItem> pullItems() {
        return items(PR_HIGH, PAGE_SIZE, true);
    }

    public static PollTarget pollTarget() {
        return new PollTarget(OWNER, REPO, ISSUE_CURSOR, PR_CURSOR, 1, 1, null, null, NOW.minusSeconds(180), NOW);
    }

    public static GitHubEvent event() {
        return new GitHubEvent(
                EventTypes.ISSUE_OPENED,
                OWNER,
                REPO,
                1423,
                "Add support for streaming queries on the local cache",
                "username",
                eventLabels(),
                "https://github.com/apache/iggy/issues/1423",
                body(1423).substring(0, 200),
                Instant.parse("2026-11-17T03:00:00Z"));
    }

    public static List<String> requiredLabels() {
        return List.of("bug", "good first issue", "help wanted");
    }

    public static List<String> eventLabels() {
        return List.of("bug", "enhancement", "good first issue", "documentation", "help wanted", "question");
    }

    public static List<DeliveryTarget> deliveryTargets() {
        UUID user = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String webhook = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz";
        return List.of(
                target(user, "22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333", webhook, List.of("bug", "good first issue")),
                target(user, "22222222-2222-2222-2222-222222222223", "33333333-3333-3333-3333-333333333334", webhook, List.of("docs")),
                target(user, "22222222-2222-2222-2222-222222222224", "33333333-3333-3333-3333-333333333335", webhook, List.of()),
                target(user, "22222222-2222-2222-2222-222222222225", "33333333-3333-3333-3333-333333333336", webhook, List.of("help wanted", "bug")));
    }

    private static DeliveryTarget target(UUID user, String subscription, String channel, String webhook, List<String> required) {
        return new DeliveryTarget(
                user,
                UUID.fromString(subscription),
                UUID.fromString(channel),
                "discord",
                true,
                webhook,
                EventTypes.ISSUE_OPENED,
                required);
    }

    private static List<RemoteItem> items(int high, int count, boolean pulls) {
        List<RemoteItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int number = high - i;
            boolean pull = pulls || number % 7 == 0;
            items.add(new RemoteItem(
                    number,
                    title(number),
                    author(number),
                    labelNames(number),
                    url(number, pull),
                    body(number),
                    Instant.parse(createdAt(number)),
                    pull));
        }
        return items;
    }

    static String title(int number) {
        return "Add support for streaming queries on cache segment " + number;
    }

    static String author(int number) {
        return "contributor" + (number % 40);
    }

    static String body(int number) {
        return "Issue " + number + " on " + OWNER + "/" + REPO + ". " + BODY_STEM + BODY_STEM + BODY_STEM;
    }

    static List<String> labelNames(int number) {
        List<String> names = new ArrayList<>(4);
        int start = number % LABEL_NAMES.length;
        for (int i = 0; i < 4; i++) {
            names.add(LABEL_NAMES[(start + i) % LABEL_NAMES.length]);
        }
        return names;
    }

    private static String url(int number, boolean pull) {
        return "https://github.com/" + OWNER + "/" + REPO + (pull ? "/pull/" : "/issues/") + number;
    }

    private static String createdAt(int number) {
        int second = number % 60;
        int minute = (number / 60) % 60;
        return "2026-11-17T03:" + pad(minute) + ":" + pad(second) + "Z";
    }

    private static String pad(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static void appendItem(StringBuilder json, int number, boolean pull) {
        String login = author(number);
        json.append("{\"id\":").append(800_000 + number);
        json.append(",\"node_id\":\"I_kwDO").append(number).append('"');
        json.append(",\"number\":").append(number);
        json.append(",\"title\":");
        quote(json, title(number));
        json.append(",\"state\":\"open\",\"locked\":false,\"comments\":").append(number % 17);
        json.append(",\"user\":{\"login\":");
        quote(json, login);
        json.append(",\"id\":").append(50_000 + (number % 40));
        json.append(",\"node_id\":\"U_kwDO").append(number % 40).append('"');
        json.append(",\"avatar_url\":\"https://avatars.githubusercontent.com/u/").append(50_000 + (number % 40)).append('"');
        json.append(",\"gravatar_id\":\"\"");
        json.append(",\"url\":\"https://api.github.com/users/").append(login).append('"');
        json.append(",\"html_url\":\"https://github.com/").append(login).append('"');
        json.append(",\"followers_url\":\"https://api.github.com/users/").append(login).append("/followers\"");
        json.append(",\"following_url\":\"https://api.github.com/users/").append(login).append("/following{/other_user}\"");
        json.append(",\"gists_url\":\"https://api.github.com/users/").append(login).append("/gists{/gist_id}\"");
        json.append(",\"starred_url\":\"https://api.github.com/users/").append(login).append("/starred{/owner}{/repo}\"");
        json.append(",\"subscriptions_url\":\"https://api.github.com/users/").append(login).append("/subscriptions\"");
        json.append(",\"organizations_url\":\"https://api.github.com/users/").append(login).append("/orgs\"");
        json.append(",\"repos_url\":\"https://api.github.com/users/").append(login).append("/repos\"");
        json.append(",\"events_url\":\"https://api.github.com/users/").append(login).append("/events{/privacy}\"");
        json.append(",\"received_events_url\":\"https://api.github.com/users/").append(login).append("/received_events\"");
        json.append(",\"type\":\"User\",\"site_admin\":false}");
        json.append(",\"labels\":[");
        int start = number % LABEL_NAMES.length;
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                json.append(',');
            }
            int index = (start + i) % LABEL_NAMES.length;
            json.append("{\"id\":").append(9000 + index);
            json.append(",\"node_id\":\"LA_kwDO").append(index).append('"');
            json.append(",\"url\":\"https://api.github.com/repos/").append(OWNER).append('/').append(REPO).append("/labels/");
            json.append(LABEL_NAMES[index].replace(" ", "%20")).append('"');
            json.append(",\"name\":");
            quote(json, LABEL_NAMES[index]);
            json.append(",\"color\":\"").append(LABEL_COLORS[index]).append('"');
            json.append(",\"default\":false,\"description\":");
            quote(json, "Label " + LABEL_NAMES[index] + " applied by repository maintainers");
            json.append('}');
        }
        json.append("],\"assignee\":null,\"assignees\":[],\"milestone\":null");
        json.append(",\"html_url\":");
        quote(json, url(number, pull));
        json.append(",\"created_at\":\"").append(createdAt(number)).append('"');
        json.append(",\"updated_at\":\"").append(createdAt(number + 60)).append('"');
        json.append(",\"closed_at\":null,\"author_association\":\"CONTRIBUTOR\",\"active_lock_reason\":null");
        json.append(",\"body\":");
        quote(json, body(number));
        json.append(",\"reactions\":{\"url\":\"https://api.github.com/repos/")
                .append(OWNER).append('/').append(REPO).append("/issues/").append(number).append("/reactions\"");
        json.append(",\"total_count\":").append(number % 9);
        json.append(",\"+1\":1,\"-1\":0,\"laugh\":0,\"hooray\":0,\"confused\":0,\"heart\":1,\"rocket\":0,\"eyes\":2}");
        json.append(",\"timeline_url\":\"https://api.github.com/repos/")
                .append(OWNER).append('/').append(REPO).append("/issues/").append(number).append("/timeline\"");
        json.append(",\"state_reason\":null");
        if (pull) {
            json.append(",\"pull_request\":{\"url\":\"https://api.github.com/repos/")
                    .append(OWNER).append('/').append(REPO).append("/pulls/").append(number).append('"');
            json.append(",\"html_url\":");
            quote(json, url(number, true));
            json.append(",\"diff_url\":\"https://github.com/").append(OWNER).append('/').append(REPO).append("/pull/").append(number).append(".diff\"");
            json.append(",\"patch_url\":\"https://github.com/").append(OWNER).append('/').append(REPO).append("/pull/").append(number).append(".patch\"}");
        }
        json.append('}');
    }

    private static void quote(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\');
            }
            json.append(c);
        }
        json.append('"');
    }
}
