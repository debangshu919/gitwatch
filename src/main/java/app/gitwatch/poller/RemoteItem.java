package app.gitwatch.poller;

import java.time.Instant;
import java.util.List;

public record RemoteItem(
        int number,
        String title,
        String author,
        List<String> labels,
        String url,
        String body,
        Instant createdAt,
        boolean pullRequest) {
}
