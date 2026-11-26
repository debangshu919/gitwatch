package app.gitwatch.notifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscordMessageFormatterTest {

    @Test
    void issueMatchesSpecFormat() {
        assertEquals(
                spec(
                        "New Issue",
                        "https://github.com/apache/iggy/issues/1423",
                        "Labels: good first issue, help wanted"),
                DiscordMessageFormatter.format(issue(List.of("good first issue", "help wanted"))));
    }

    @Test
    void pullRequestUsesPullRequestTitleAndUrl() {
        GitHubEvent event = new GitHubEvent(
                EventTypes.PR_OPENED,
                "apache",
                "iggy",
                1423,
                "Add support for streaming queries",
                "username",
                List.of("bug"),
                "https://github.com/apache/iggy/pull/1423",
                "",
                Instant.parse("2026-11-17T03:00:00Z"));
        assertEquals(
                spec("New Pull Request", "https://github.com/apache/iggy/pull/1423", "Labels: bug"),
                DiscordMessageFormatter.format(event));
    }

    @Test
    void omitsLabelsLineWhenEmpty() {
        String body = DiscordMessageFormatter.format(issue(List.of()));
        assertFalse(body.contains("Labels:"));
        assertEquals(spec("New Issue", "https://github.com/apache/iggy/issues/1423", null), body);
    }

    private static String spec(String kind, String url, String labelsLine) {
        StringBuilder body = new StringBuilder();
        body.append("🟢 ").append(kind).append(" in apache/iggy\n\n");
        body.append("#1423 – Add support for streaming queries\n");
        body.append("Author: @username");
        if (labelsLine != null) {
            body.append("\n").append(labelsLine);
        }
        body.append("\n\n").append(url);
        return body.toString();
    }

    private static GitHubEvent issue(List<String> labels) {
        return new GitHubEvent(
                EventTypes.ISSUE_OPENED,
                "apache",
                "iggy",
                1423,
                "Add support for streaming queries",
                "username",
                labels,
                "https://github.com/apache/iggy/issues/1423",
                "First 200 characters...",
                Instant.parse("2026-11-17T03:00:00Z"));
    }
}
