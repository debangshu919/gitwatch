package app.gitwatch.github;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiException;
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
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpPublicRepoLookup implements PublicRepoLookup {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String apiBase;
    private final String token;
    private final Duration timeout;

    @Autowired
    public HttpPublicRepoLookup(HttpClient http, ObjectMapper mapper, AppProperties properties) {
        this(
                http,
                mapper,
                properties.getGithub().getApiBase(),
                properties.getGithub().getToken(),
                properties.getNotifier().getTimeout());
    }

    public HttpPublicRepoLookup(HttpClient http, ObjectMapper mapper, String apiBase, String token, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.apiBase = apiBase == null ? "https://api.github.com" : apiBase;
        this.token = token == null ? "" : token;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    @Override
    public void requirePublic(String owner, String repo) {
        String base = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        URI uri = URI.create(base + "/repos/" + encode(owner) + "/" + encode(repo));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
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
            if (status == 404) {
                throw new ApiException(400, ApiCodes.NOT_PUBLIC, "Repository was not found or is not public");
            }
            if (status < 200 || status >= 300) {
                throw new ApiException(502, ApiCodes.UPSTREAM_UNAVAILABLE, "GitHub could not confirm the repository");
            }
            JsonNode node = mapper.readTree(response.body());
            if (node.path("private").asBoolean(false)) {
                throw new ApiException(400, ApiCodes.PRIVATE_REPO, "Private repositories are not supported");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, ApiCodes.UPSTREAM_UNAVAILABLE, "GitHub could not confirm the repository");
        } catch (IOException | IllegalArgumentException ex) {
            throw new ApiException(502, ApiCodes.UPSTREAM_UNAVAILABLE, "GitHub could not confirm the repository");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
