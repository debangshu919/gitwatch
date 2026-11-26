package app.gitwatch.github;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiException;
import app.gitwatch.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpGitHubOAuthClient implements GitHubOAuthClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String oauthBase;
    private final String apiBase;
    private final Duration timeout;
    private final AppProperties properties;

    @Autowired
    public HttpGitHubOAuthClient(HttpClient http, ObjectMapper mapper, AppProperties properties) {
        this(http, mapper, "https://github.com", properties.getGithub().getApiBase(), properties.getNotifier().getTimeout(), properties);
    }

    public HttpGitHubOAuthClient(
            HttpClient http,
            ObjectMapper mapper,
            String oauthBase,
            String apiBase,
            Duration timeout,
            AppProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.oauthBase = trimSlash(oauthBase == null ? "https://github.com" : oauthBase);
        this.apiBase = trimSlash(apiBase == null ? "https://api.github.com" : apiBase);
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.properties = properties;
    }

    @Override
    public GitHubIdentity exchange(String code, String redirectUri) {
        if (!properties.getOauth().isConfigured()) {
            throw new ApiException(503, ApiCodes.OAUTH_NOT_CONFIGURED, "GitHub OAuth is not configured");
        }
        String accessToken = token(code, redirectUri);
        JsonNode user = get("/user", accessToken);
        String email = text(user, "email");
        if (email == null || email.isBlank()) {
            email = primaryEmail(accessToken);
        }
        String name = text(user, "name");
        if (name == null || name.isBlank()) {
            name = text(user, "login");
        }
        return new GitHubIdentity(
                user.path("id").asText(""),
                text(user, "login"),
                email,
                name,
                text(user, "avatar_url"),
                accessToken);
    }

    private String token(String code, String redirectUri) {
        ObjectNode body = mapper.createObjectNode();
        body.put("client_id", properties.getOauth().getClientId());
        body.put("client_secret", properties.getOauth().getClientSecret());
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(oauthBase + "/login/oauth/access_token"))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "gitwatch")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failed();
            }
            JsonNode node = mapper.readTree(response.body());
            String accessToken = text(node, "access_token");
            if (accessToken == null || accessToken.isBlank() || node.hasNonNull("error")) {
                throw failed();
            }
            return accessToken;
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed();
        } catch (IOException | IllegalArgumentException ex) {
            throw failed();
        }
    }

    private String primaryEmail(String accessToken) {
        JsonNode emails = get("/user/emails", accessToken);
        if (!emails.isArray()) {
            return null;
        }
        String fallback = null;
        for (JsonNode email : emails) {
            if (!email.path("verified").asBoolean(false)) {
                continue;
            }
            String value = text(email, "email");
            if (value == null || value.isBlank()) {
                continue;
            }
            if (email.path("primary").asBoolean(false)) {
                return value;
            }
            if (fallback == null) {
                fallback = value;
            }
        }
        return fallback;
    }

    private JsonNode get(String path, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(timeout)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "gitwatch")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failed();
            }
            return mapper.readTree(response.body());
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed();
        } catch (IOException | IllegalArgumentException ex) {
            throw failed();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private static String trimSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static ApiException failed() {
        return new ApiException(502, ApiCodes.OAUTH_FAILED, "GitHub login could not be completed");
    }
}
