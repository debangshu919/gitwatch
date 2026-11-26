package app.gitwatch.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.gitwatch.api.ApiException;
import org.junit.jupiter.api.Test;

class GitHubUrlParserTest {

    @Test
    void acceptsPublicGitHubRepoUrls() {
        assertEquals(new GitHubUrlParser.Coordinates("apache", "iggy"), GitHubUrlParser.parse("https://github.com/apache/iggy"));
        assertEquals(new GitHubUrlParser.Coordinates("apache", "iggy"), GitHubUrlParser.parse("https://github.com/apache/iggy.git"));
        assertEquals(new GitHubUrlParser.Coordinates("apache", "iggy"), GitHubUrlParser.parse("https://github.com/apache/iggy/"));
        assertEquals(new GitHubUrlParser.Coordinates("apache", "iggy"), GitHubUrlParser.parse("http://www.github.com/apache/iggy"));
        assertEquals(new GitHubUrlParser.Coordinates("apache", "iggy"), GitHubUrlParser.parse("https://github.com/Apache/Iggy"));
    }

    @Test
    void rejectsNonGitHubAndIncompleteUrls() {
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://gitlab.com/apache/iggy"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://github.com.evil.com/apache/iggy"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://github.com/apache"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://github.com/apache/iggy/issues/1"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("not a url"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse(null));
    }
}
