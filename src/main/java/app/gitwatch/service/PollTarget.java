package app.gitwatch.service;

import app.gitwatch.model.RepoPollState;
import java.time.Instant;

public record PollTarget(
        String owner,
        String repo,
        Integer lastIssueNumber,
        Integer lastPrNumber,
        int issuePage,
        int prPage,
        Integer issueHighWater,
        Integer prHighWater,
        Instant lastPolledAt,
        Instant nextPollAt) {

    public PollTarget {
        if (issuePage < 1) {
            issuePage = 1;
        }
        if (prPage < 1) {
            prPage = 1;
        }
    }

    public PollTarget(
            String owner,
            String repo,
            Integer lastIssueNumber,
            Integer lastPrNumber,
            Instant lastPolledAt,
            Instant nextPollAt) {
        this(owner, repo, lastIssueNumber, lastPrNumber, 1, 1, null, null, lastPolledAt, nextPollAt);
    }

    public static PollTarget from(RepoPollState state) {
        return new PollTarget(
                state.getId().getOwner(),
                state.getId().getRepo(),
                state.getLastIssueNumber(),
                state.getLastPrNumber(),
                state.getIssuePage(),
                state.getPrPage(),
                state.getIssueHighWater(),
                state.getPrHighWater(),
                state.getLastPolledAt(),
                state.getNextPollAt());
    }

    public PollTarget schedule(Instant polledAt, Instant nextAt) {
        return new PollTarget(
                owner,
                repo,
                lastIssueNumber,
                lastPrNumber,
                issuePage,
                prPage,
                issueHighWater,
                prHighWater,
                polledAt,
                nextAt);
    }
}
