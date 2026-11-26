package app.gitwatch.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PollLockSqlTest {

    @Test
    void locksDueRowsWithSkipLockedAndRequiresAnActiveSubscription() {
        String sql = PollLockSql.statement(10, ":now");
        assertTrue(sql.contains("FOR UPDATE OF rps SKIP LOCKED"));
        assertTrue(sql.contains("SELECT 1"));
        assertTrue(sql.contains("subscriptions"));
        assertTrue(sql.contains("s.is_active = TRUE"));
        assertTrue(sql.contains("r.is_active = TRUE"));
        assertTrue(sql.contains("next_poll_at"));
    }
}
