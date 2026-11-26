package app.gitwatch.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.gitwatch.poller.GitHubClientException;
import app.gitwatch.poller.RemoteItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks GitHubClient.parse to a tree walk of the same JSON. A faster parser has to match this,
 * including missing fields, nulls, textual labels, and pull_request presence.
 */
class GitHubClientParseOracleTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HotPathFakes.IssuesParser parser = new HotPathFakes.IssuesParser();

    @Test
    void parseMatchesTreeWalk() throws Exception {
        List<String> pages = List.of(
                HotPathFixtures.issuesPageJson(),
                "[]",
                "[{\"number\":1}]",
                "[{\"number\":\"42\",\"title\":7,\"body\":null,\"user\":null,\"labels\":null,\"html_url\":null,\"created_at\":null,\"pull_request\":null}]",
                "[{\"number\":3,\"title\":\"t\",\"user\":{\"login\":\"a\",\"id\":9},\"labels\":[\"bug\",{\"name\":\"docs\"},{\"color\":\"fff\"},{\"name\":null},null],\"html_url\":\"https://github.com/apache/iggy/issues/3\",\"body\":\"hello\",\"created_at\":\"2026-11-17T03:00:00Z\",\"pull_request\":{\"url\":\"u\",\"html_url\":\"h\"},\"reactions\":{\"total_count\":1}}]",
                "[{\"user\":{\"id\":1},\"labels\":{\"name\":\"bug\"},\"pull_request\":false,\"title\":null,\"body\":true}]",
                "[null,1,\"x\",{\"number\":9,\"title\":\"n\",\"user\":{\"login\":\"\"}}]",
                "[{\"body\":\"line\\nquote \\\"q\\\"\",\"title\":\"café 🟢\"}]",
                "[{\"number\":3000000000,\"title\":1.5,\"html_url\":false,\"labels\":[{\"name\":12}]}]",
                "[{\"created_at\":\"2026-11-17T03:00:00Z\",\"user\":{\"login\":7},\"labels\":[]}]",
                "[{\"title\":1e2,\"number\":1.9,\"body\":\"\",\"html_url\":12,\"labels\":[{\"name\":1.5}]}]",
                "[{},{\"pull_request\":{\"diff_url\":\"d\"},\"labels\":[\"good first issue\"]}]");
        for (String body : pages) {
            assertEquals(oracle(body, "issues"), parser.parse(body, "issues"), body);
            assertEquals(oracle(body, "pulls"), parser.parse(body, "pulls"), "pulls " + body);
        }
    }

    @Test
    void nonArrayUsesTheSameError() {
        GitHubClientException object = assertThrows(GitHubClientException.class, () -> parser.parse("{\"number\":1}"));
        assertTrue(object.getMessage().contains("not an array"));
        GitHubClientException nil = assertThrows(GitHubClientException.class, () -> parser.parse("null"));
        assertTrue(nil.getMessage().contains("not an array"));
    }

    @Test
    void invalidJsonStillFails() {
        assertThrows(Exception.class, () -> parser.parse(""));
        assertThrows(Exception.class, () -> parser.parse("["));
        assertThrows(Exception.class, () -> parser.parse("not-json"));
    }

    private List<RemoteItem> oracle(String body, String kind) throws Exception {
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

    private static RemoteItem toItem(JsonNode node, String kind) {
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

    private static List<String> labels(JsonNode node) {
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
}
