package app.gitwatch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record GitHubEvent(
        @JsonProperty("event_type") String eventType,
        String owner,
        String repo,
        int number,
        String title,
        String author,
        List<String> labels,
        String url,
        @JsonProperty("body_excerpt") String bodyExcerpt,
        @JsonProperty("created_at") Instant createdAt) {}
