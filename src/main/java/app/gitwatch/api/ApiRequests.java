package app.gitwatch.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ApiRequests {

    private ApiRequests() {
    }

    public record RegisterRequest(String email, String password, String name) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record NameUpdate(String name) {
    }

    public record RepoCreateRequest(String url) {
    }

    public record WatchUpdate(@JsonProperty("is_active") Boolean active) {
    }

    public record ChannelConfigRequest(
            @JsonProperty("webhook_url") String webhookUrl,
            @JsonProperty("chat_id") String chatId) {
    }

    public record ChannelCreateRequest(String type, String name, ChannelConfigRequest config) {
    }

    public record FiltersRequest(List<String> labels) {
    }

    public record SubscriptionCreateRequest(
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("event_type") String eventType,
            FiltersRequest filters) {
    }

    public record SubscriptionUpdateRequest(
            @JsonProperty("event_type") String eventType,
            FiltersRequest filters,
            @JsonProperty("is_active") Boolean active) {
    }
}
