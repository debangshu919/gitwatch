package app.gitwatch.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import app.gitwatch.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpGitHubOAuthClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void exchangesTheCodeWithoutCallingGitHub() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"access_token\":\"gho_test\",\"token_type\":\"bearer\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\":42,\"login\":\"octo\",\"name\":\"Octo\",\"email\":null,\"avatar_url\":\"https://example.invalid/a.png\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"email\":\"octo@example.com\",\"verified\":true,\"primary\":true}]"));
        AppProperties properties = new AppProperties();
        properties.getOauth().setClientId("client");
        properties.getOauth().setClientSecret("secret");
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String base = server.url("/").toString();
        HttpGitHubOAuthClient client = new HttpGitHubOAuthClient(http, new ObjectMapper(), base, base, Duration.ofSeconds(2), properties);

        GitHubIdentity identity = client.exchange("code-1", "http://localhost/v1/auth/github/callback");

        assertEquals("42", identity.providerId());
        assertEquals("octo@example.com", identity.email());
        assertFalse(identity.toString().contains("gho_test"));
        RecordedRequest tokenRequest = server.takeRequest();
        assertEquals("/login/oauth/access_token", tokenRequest.getPath());
        assertEquals(true, tokenRequest.getBody().readUtf8().contains("code-1"));
        assertEquals(3, server.getRequestCount());
    }
}
