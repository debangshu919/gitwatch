package app.gitwatch.github;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.gitwatch.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpPublicRepoLookupTest {

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
    void acceptsAPublicRepository() {
        server.enqueue(new MockResponse().setBody("{\"private\":false}").setResponseCode(200));
        HttpPublicRepoLookup lookup = lookup();

        assertDoesNotThrow(() -> lookup.requirePublic("apache", "iggy"));
    }

    @Test
    void rejectsAPrivateRepository() {
        server.enqueue(new MockResponse().setBody("{\"private\":true}").setResponseCode(200));
        HttpPublicRepoLookup lookup = lookup();

        ApiException ex = assertThrows(ApiException.class, () -> lookup.requirePublic("acme", "secret"));
        assertEquals(400, ex.status());
        assertEquals("PRIVATE_REPO", ex.code());
    }

    @Test
    void rejectsAMissingRepositoryAsNotPublic() {
        server.enqueue(new MockResponse().setResponseCode(404));
        HttpPublicRepoLookup lookup = lookup();

        ApiException ex = assertThrows(ApiException.class, () -> lookup.requirePublic("acme", "missing"));
        assertEquals("NOT_PUBLIC", ex.code());
    }

    private HttpPublicRepoLookup lookup() {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return new HttpPublicRepoLookup(http, new ObjectMapper(), server.url("/").toString(), "", Duration.ofSeconds(2));
    }
}
