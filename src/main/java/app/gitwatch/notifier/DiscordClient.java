package app.gitwatch.notifier;

import app.gitwatch.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DiscordClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration timeout;

    @Autowired
    public DiscordClient(@Qualifier("discordHttpClient") HttpClient http, ObjectMapper mapper, AppProperties properties) {
        this(http, mapper, properties.getNotifier().getTimeout());
    }

    public DiscordClient(HttpClient http, ObjectMapper mapper, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
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
            if (status >= 200 && status < 300) {
                return DiscordResponse.success();
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
