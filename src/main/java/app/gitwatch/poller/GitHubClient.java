package app.gitwatch.poller;

import app.gitwatch.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GitHubClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String apiBase;
    private final String token;
    private final int minRemaining;
    private final Duration rateLimitDelay;
    private final int pageSize;
    private final Duration timeout;

    @Autowired
    public GitHubClient(HttpClient http, ObjectMapper mapper, Clock clock, AppProperties properties) {
        this(
                http,
                mapper,
                clock,
                properties.getGithub().getApiBase(),
                properties.getGithub().getToken(),
                properties.getGithub().getMinRemaining(),
                properties.getGithub().getRateLimitDelay(),
                properties.getPoller().getPageSize(),
                properties.getNotifier().getTimeout());
    }

    public GitHubClient(
            HttpClient http,
            ObjectMapper mapper,
            Clock clock,
            String apiBase,
            String token,
            int minRemaining,
            Duration rateLimitDelay,
            int pageSize,
            Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
        this.apiBase = apiBase == null ? "https://api.github.com" : apiBase;
        this.token = token == null ? "" : token;
        this.minRemaining = minRemaining;
        this.rateLimitDelay = rateLimitDelay == null ? Duration.ofMinutes(15) : rateLimitDelay;
        this.pageSize = pageSize;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    public GitHubPage listIssues(String owner, String repo, int page) {
        return list("issues", owner, repo, page);
    }

    public GitHubPage listPulls(String owner, String repo, int page) {
        return list("pulls", owner, repo, page);
    }

    private GitHubPage list(String kind, String owner, String repo, int page) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(kind, owner, repo, page))
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "gitwatch")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            int remaining = remaining(response);
            Instant resetAt = resetAt(response);
            if (status == 429 || isSecondaryRateLimit(status, response, remaining)) {
                throw new GitHubRateLimitException(retryAt(response, resetAt));
            }
            if (status < 200 || status >= 300) {
                throw new GitHubClientException(status, "GitHub returned " + status);
            }
            return new GitHubPage(parse(response.body(), kind), remaining, resetAt, remaining < minRemaining);
        } catch (GitHubRateLimitException | GitHubClientException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubClientException(0, "GitHub request interrupted");
        } catch (IOException ex) {
            throw new GitHubClientException(0, "GitHub request failed");
        }
    }

    private List<RemoteItem> parse(String body, String kind) throws IOException {
        JsonNode root = mapper.readTree(body);
        if (!root.isArray()) {
            throw new GitHubClientException(0, "GitHub list was not an array");
        }
        List<RemoteItem> items = new ArrayList<>();
        for (JsonNode node : root) {
            items.add(toItem(node, kind));
        }
        return items;
    }

    private RemoteItem toItem(JsonNode node, String kind) {
        boolean pullRequest = "pulls".equals(kind) || node.hasNonNull("pull_request");
        JsonNode user = node.get("user");
        String author =
                user != null && user.hasNonNull("login") ? user.get("login").asText() : "unknown";
        String url = node.hasNonNull("html_url") ? node.get("html_url").asText() : "";
        Instant createdAt = node.hasNonNull("created_at")
                ? Instant.parse(node.get("created_at").asText())
                : Instant.EPOCH;
        return new RemoteItem(
                node.path("number").asInt(),
                node.path("title").asText(""),
                author,
                labels(node.get("labels")),
                url,
                node.path("body").isNull() ? "" : node.path("body").asText(""),
                createdAt,
                pullRequest);
    }

    private List<String> labels(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return names;
        }
        for (JsonNode label : node) {
            if (label.isTextual()) {
                names.add(label.asText());
            } else if (label.hasNonNull("name")) {
                names.add(label.get("name").asText());
            }
        }
        return names;
    }

    private URI endpoint(String kind, String owner, String repo, int page) {
        String base = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        String path = "/repos/" + encode(owner) + "/" + encode(repo) + "/" + kind;
        String query = "state=all&sort=created&direction=desc&per_page=" + pageSize + "&page=" + page;
        return URI.create(base + path + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private int remaining(HttpResponse<String> response) {
        return response.headers()
                .firstValue("x-ratelimit-remaining")
                .map(Integer::parseInt)
                .orElse(Integer.MAX_VALUE);
    }

    private Instant resetAt(HttpResponse<String> response) {
        return response.headers()
                .firstValue("x-ratelimit-reset")
                .map(Long::parseLong)
                .map(Instant::ofEpochSecond)
                .orElse(clock.instant().plus(rateLimitDelay));
    }

    private Instant retryAt(HttpResponse<String> response, Instant resetAt) {
        return response.headers()
                .firstValue("retry-after")
                .map(this::parseRetryAfter)
                .orElse(resetAt);
    }

    private Instant parseRetryAfter(String value) {
        try {
            return clock.instant().plusSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return clock.instant().plus(rateLimitDelay);
        }
    }

    private boolean isSecondaryRateLimit(int status, HttpResponse<String> response, int remaining) {
        if (status != 403) {
            return false;
        }
        if (remaining == 0) {
            return true;
        }
        String body = response.body() == null ? "" : response.body().toLowerCase();
        return body.contains("rate limit");
    }
}
