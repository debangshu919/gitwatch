package app.gitwatch.repository;

public final class PollLockSql {

    private PollLockSql() {
    }

    public static String statement(int batch, String nowParam) {
        if (batch < 1 || batch > 100) {
            throw new IllegalArgumentException("batch out of range");
        }
        return """
                SELECT rps.owner, rps.repo
                FROM repo_poll_state rps
                WHERE (rps.next_poll_at IS NULL OR rps.next_poll_at <= %s)
                  AND EXISTS (
                    SELECT 1
                    FROM repos r
                    INNER JOIN subscriptions s ON s.repo_id = r.id
                    WHERE r.owner = rps.owner
                      AND r.repo = rps.repo
                      AND r.is_active = TRUE
                      AND s.is_active = TRUE
                  )
                ORDER BY rps.next_poll_at ASC NULLS FIRST
                LIMIT %d
                FOR UPDATE OF rps SKIP LOCKED
                """.formatted(nowParam, batch);
    }
}
