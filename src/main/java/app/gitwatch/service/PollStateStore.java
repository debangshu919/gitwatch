package app.gitwatch.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface PollStateStore {

    List<PollTarget> claimDue(Instant now, int limit, Duration lease);

    void saveCursors(
            String owner,
            String repo,
            Integer lastIssueNumber,
            Integer lastPrNumber,
            Instant lastPolledAt,
            Instant nextPollAt);
}
