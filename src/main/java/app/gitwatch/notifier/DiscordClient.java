package app.gitwatch.notifier;

import app.gitwatch.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DiscordClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final Duration backoff;

    @Autowired
    public DiscordClient(HttpClient http, ObjectMapper mapper, AppProperties properties) {
        this(http, mapper, properties.getNotifier().getTimeout(), properties.getNotifier().getBackoff());
    }

    public DiscordClient(HttpClient http, ObjectMapper mapper, Duration timeout) {
        this(http, mapper, timeout, Duration.ofSeconds(2));
    }

    public DiscordClient(HttpClient http, ObjectMapper mapper, Duration timeout, Duration backoff) {
        this.http = http;
        this.mapper = mapper;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.backoff = backoff == null || backoff.isNegative() ? Duration.ofSeconds(2) : backoff;
    }

    public DiscordResponse post(String webhookUrl, String content) {
        URI uri = webhook(webhookUrl);
        if (uri == null) {
            return DiscordResponse.permanent("invalid webhook");
        }
        try {
            String json = mapper.writeValueAsString(Map.of("content", content));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "gitwatch")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            // 429 is Discord's throttle and is retried. Other 4xx means the webhook is bad and is not retried.
            // 5xx and IOException are retried. Never log the URL: it is the credential.
            if (status >= 200 && status < 300) {
                return DiscordResponse.success();
            }
            if (status == 429) {
                return DiscordResponse.throttled(retryDelay(response));
            }
            if (status >= 400 && status < 500) {
                return DiscordResponse.permanent("Discord returned " + status);
            }
            return DiscordResponse.transientFailure("Discord returned " + status);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DiscordResponse.transientFailure("Discord request interrupted");
        } catch (IOException ex) {
            return DiscordResponse.transientFailure("Discord request failed");
        } catch (IllegalArgumentException ex) {
            return DiscordResponse.permanent("invalid webhook");
        }
    }

    private Duration retryDelay(HttpResponse<String> response) {
        Duration header = headerDelay(response);
        if (header != null) {
            return header;
        }
        Duration body = bodyDelay(response.body());
        if (body != null) {
            return body;
        }
        return backoff;
    }

    private Duration headerDelay(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").map(this::parseSeconds).orElse(null);
    }

    private Duration bodyDelay(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode retryAfter = mapper.readTree(body).get("retry_after");
            if (retryAfter == null || !retryAfter.isNumber()) {
                return null;
            }
            return seconds(retryAfter.asDouble());
        } catch (IOException ex) {
            return null;
        }
    }

    private Duration parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return seconds(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Duration seconds(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return Duration.ZERO;
        }
        double millis = value * 1000.0;
        if (millis >= Long.MAX_VALUE) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
        return Duration.ofMillis(Math.round(millis));
    }

    private URI webhook(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(webhookUrl);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
