package app.gitwatch.service;

import app.gitwatch.model.RepoPollState;
import java.time.Instant;

public record PollTarget(
        String owner,
        String repo,
        Integer lastIssueNumber,
        Integer lastPrNumber,
        Instant lastPolledAt,
        Instant nextPollAt) {

    public static PollTarget from(RepoPollState state) {
        return new PollTarget(
                state.getId().getOwner(),
                state.getId().getRepo(),
                state.getLastIssueNumber(),
                state.getLastPrNumber(),
                state.getLastPolledAt(),
                state.getNextPollAt());
    }
}
