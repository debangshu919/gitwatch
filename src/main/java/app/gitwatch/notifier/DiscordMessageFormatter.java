package app.gitwatch.notifier;

import app.gitwatch.model.EventTypes;
import app.gitwatch.model.GitHubEvent;
import java.util.ArrayList;
import java.util.List;

public final class DiscordMessageFormatter {

    private DiscordMessageFormatter() {
    }

    public static String format(GitHubEvent event) {
        String kind = EventTypes.PR_OPENED.equals(event.eventType()) ? "New Pull Request" : "New Issue";
        StringBuilder body = new StringBuilder();
        body.append("🟢 ").append(kind).append(" in ").append(event.owner()).append("/").append(event.repo());
        body.append("\n\n");
        body.append("#").append(event.number()).append(" – ").append(event.title());
        body.append("\n");
        body.append("Author: ").append(author(event.author()));
        List<String> labels = presentLabels(event.labels());
        if (!labels.isEmpty()) {
            body.append("\n");
            body.append("Labels: ").append(String.join(", ", labels));
        }
        body.append("\n\n");
        body.append(event.url());
        return body.toString();
    }

    private static String author(String login) {
        if (login == null || login.isBlank()) {
            return "@unknown";
        }
        return login.startsWith("@") ? login : "@" + login;
    }

    private static List<String> presentLabels(List<String> labels) {
        List<String> present = new ArrayList<>();
        if (labels == null) {
            return present;
        }
        for (String label : labels) {
            if (label != null && !label.isBlank()) {
                present.add(label);
            }
        }
        return present;
    }
}
