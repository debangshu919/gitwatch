package app.gitwatch.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ApiEnvelopes {

    private ApiEnvelopes() {
    }

    public record DataResponse<T>(T data) {
    }

    public record ErrorBody(String code, String message) {
    }

    public record ErrorResponse(ErrorBody error) {
    }

    public record Pagination(int limit, int offset, long total) {
    }

    public record PageResponse<T>(List<T> data, Pagination pagination) {
    }

    public record StatusBody(String status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthUser(String id, String email, String name) {
    }

    public record AuthResponse(AuthUser user, String token) {
    }

    public record Profile(
            String id,
            String email,
            String name,
            @JsonProperty("avatar_url") String avatarUrl) {
    }

    public record RepoView(
            String id,
            String owner,
            String repo,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("is_active") boolean active) {
    }

    public record ChannelView(
            String id,
            String type,
            String name,
            @JsonProperty("is_active") boolean active) {
    }

    public record FiltersView(List<String> labels) {
    }

    public record SubscriptionView(
            String id,
            @JsonProperty("repo_id") String repoId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("event_type") String eventType,
            FiltersView filters,
            @JsonProperty("is_active") boolean active) {
    }

    public record NotificationView(
            String id,
            @JsonProperty("event_type") String eventType,
            String title,
            @JsonProperty("github_url") String githubUrl,
            String status,
            @JsonProperty("created_at") String createdAt) {
    }
}
