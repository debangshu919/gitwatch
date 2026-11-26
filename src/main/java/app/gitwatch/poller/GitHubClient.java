package app.gitwatch.poller;

import app.gitwatch.config.AppProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.InputCoercionException;
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
        try (JsonParser parser = mapper.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                return parseTree(body, kind);
            }
            List<RemoteItem> items = new ArrayList<>();
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY) {
                    return items;
                }
                if (token == JsonToken.START_OBJECT) {
                    items.add(readObject(parser, kind));
                } else {
                    items.add(toItem(parser.readValueAsTree(), kind));
                }
            }
        }
    }

    private List<RemoteItem> parseTree(String body, String kind) throws IOException {
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

    private RemoteItem readObject(JsonParser parser, String kind) throws IOException {
        int number = 0;
        String title = "";
        String author = "unknown";
        String url = "";
        String body = "";
        Instant createdAt = Instant.EPOCH;
        boolean pullRequest = "pulls".equals(kind);
        List<String> labels = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if (value == null) {
                break;
            }
            if ("number".equals(field)) {
                number = scalarInt(parser, value);
            } else if ("title".equals(field)) {
                title = scalarTextOrEmpty(parser, value);
            } else if ("html_url".equals(field)) {
                url = scalarTextOrEmpty(parser, value);
            } else if ("body".equals(field)) {
                body = scalarTextOrEmpty(parser, value);
            } else if ("created_at".equals(field)) {
                createdAt = createdAt(parser, value);
            } else if ("pull_request".equals(field)) {
                if (value != JsonToken.VALUE_NULL) {
                    pullRequest = true;
                    skipValue(parser, value);
                }
            } else if ("user".equals(field)) {
                author = readAuthor(parser, value);
            } else if ("labels".equals(field)) {
                labels = readLabels(parser, value);
            } else {
                skipValue(parser, value);
            }
        }
        return new RemoteItem(number, title, author, labels, url, body, createdAt, pullRequest);
    }

    private static String readAuthor(JsonParser parser, JsonToken value) throws IOException {
        if (value != JsonToken.START_OBJECT) {
            skipValue(parser, value);
            return "unknown";
        }
        String author = "unknown";
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken child = parser.nextToken();
            if (!"login".equals(field)) {
                skipValue(parser, child);
                continue;
            }
            if (child == JsonToken.VALUE_NULL || child == null) {
                author = "unknown";
            } else if (child == JsonToken.START_OBJECT || child == JsonToken.START_ARRAY) {
                parser.skipChildren();
                author = "";
            } else {
                String text = scalarText(parser, child);
                author = text == null ? "unknown" : text;
            }
        }
        return author;
    }

    private static List<String> readLabels(JsonParser parser, JsonToken value) throws IOException {
        if (value != JsonToken.START_ARRAY) {
            skipValue(parser, value);
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonToken element = parser.currentToken();
            if (element == JsonToken.VALUE_STRING) {
                names.add(parser.getText());
            } else if (element == JsonToken.START_OBJECT) {
                String name = readLabelName(parser);
                if (name != null) {
                    names.add(name);
                }
            } else {
                skipValue(parser, element);
            }
        }
        return names;
    }

    private static String readLabelName(JsonParser parser) throws IOException {
        String name = null;
        boolean present = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken child = parser.nextToken();
            if ("name".equals(field) && child != JsonToken.VALUE_NULL && child != null) {
                if (child == JsonToken.START_OBJECT || child == JsonToken.START_ARRAY) {
                    parser.skipChildren();
                    name = "";
                    present = true;
                } else {
                    name = scalarText(parser, child);
                    present = name != null;
                }
            } else {
                skipValue(parser, child);
            }
        }
        return present ? name : null;
    }

    private static Instant createdAt(JsonParser parser, JsonToken value) throws IOException {
        if (value == JsonToken.VALUE_NULL || value == null) {
            return Instant.EPOCH;
        }
        String text = scalarText(parser, value);
        return Instant.parse(text == null ? "" : text);
    }

    private static String scalarTextOrEmpty(JsonParser parser, JsonToken value) throws IOException {
        String text = scalarText(parser, value);
        return text == null ? "" : text;
    }

    private static String scalarText(JsonParser parser, JsonToken value) throws IOException {
        if (value == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        if (value == JsonToken.VALUE_NUMBER_INT || value == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getNumberValue().toString();
        }
        if (value == JsonToken.VALUE_TRUE) {
            return "true";
        }
        if (value == JsonToken.VALUE_FALSE) {
            return "false";
        }
        if (value == JsonToken.VALUE_NULL || value == null) {
            return null;
        }
        skipValue(parser, value);
        return "";
    }

    private static int scalarInt(JsonParser parser, JsonToken value) throws IOException {
        if (value == JsonToken.VALUE_NUMBER_INT) {
            try {
                return (int) parser.getLongValue();
            } catch (InputCoercionException ex) {
                return parser.getNumberValue().intValue();
            }
        }
        if (value == JsonToken.VALUE_NUMBER_FLOAT) {
            return (int) parser.getDoubleValue();
        }
        if (value == JsonToken.VALUE_STRING) {
            return parseIntText(parser.getText());
        }
        if (value == JsonToken.VALUE_TRUE) {
            return 1;
        }
        if (value == JsonToken.VALUE_FALSE || value == JsonToken.VALUE_NULL || value == null) {
            return 0;
        }
        skipValue(parser, value);
        return 0;
    }

    private static int parseIntText(String text) {
        if (text == null) {
            return 0;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            try {
                return (int) Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    private static void skipValue(JsonParser parser, JsonToken value) throws IOException {
        if (value != null && value.isStructStart()) {
            parser.skipChildren();
        }
    }

    private RemoteItem toItem(JsonNode node, String kind) {
        boolean pullRequest = "pulls".equals(kind) || node.hasNonNull("pull_request");
        JsonNode user = node.get("user");
        String author = user != null && user.hasNonNull("login") ? user.get("login").asText() : "unknown";
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
        return response.headers().firstValue("x-ratelimit-remaining").map(Integer::parseInt).orElse(Integer.MAX_VALUE);
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
