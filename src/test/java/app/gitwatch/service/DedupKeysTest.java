package app.gitwatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DedupKeysTest {

    @Test
    void keyAndTtlMatchTheContract() {
        assertEquals("dedup:apache/iggy:issue.opened:1423", DedupKeys.key("apache", "iggy", "issue.opened", 1423));
        assertEquals(
                "delivered:22222222-2222-2222-2222-222222222222:issue.opened:1423",
                DedupKeys.deliveredKey("22222222-2222-2222-2222-222222222222", "issue.opened", 1423));
        assertEquals(
                "failed:22222222-2222-2222-2222-222222222222:issue.opened:1423",
                DedupKeys.failedKey("22222222-2222-2222-2222-222222222222", "issue.opened", 1423));
        assertEquals(Duration.ofDays(30), DedupKeys.TTL);
        assertEquals(Duration.ofMinutes(2), DedupKeys.IN_FLIGHT);
    }
}
