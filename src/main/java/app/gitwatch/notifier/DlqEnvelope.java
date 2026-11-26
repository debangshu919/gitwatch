package app.gitwatch.notifier;

import app.gitwatch.model.GitHubEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

public final class DlqEnvelope {

    private DlqEnvelope() {
    }

    public static String write(
            ObjectMapper mapper,
            GitHubEvent event,
            String rawEvent,
            String subscriptionId,
            String error,
            Instant failedAt) {
        ObjectNode node = mapper.createObjectNode();
        node.set("event", eventNode(mapper, event, rawEvent));
        if (subscriptionId == null) {
            node.putNull("subscription_id");
        } else {
            node.put("subscription_id", subscriptionId);
        }
        node.put("error", error == null || error.isBlank() ? "delivery failed" : error);
        node.put("failed_at", failedAt.toString());
        try {
            return mapper.writeValueAsString(node);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encode notification dlq", ex);
        }
    }

    private static JsonNode eventNode(ObjectMapper mapper, GitHubEvent event, String rawEvent) {
        if (event != null) {
            return mapper.valueToTree(event);
        }
        if (rawEvent == null || rawEvent.isBlank()) {
            return mapper.nullNode();
        }
        try {
            JsonNode parsed = mapper.readTree(rawEvent);
            if (parsed.isObject()) {
                return parsed;
            }
        } catch (Exception ex) {
            return mapper.nullNode();
        }
        return mapper.nullNode();
    }
}
