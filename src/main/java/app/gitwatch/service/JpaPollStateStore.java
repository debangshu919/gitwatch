package app.gitwatch.service;

import app.gitwatch.model.RepoPollState;
import app.gitwatch.model.RepoPollStateId;
import app.gitwatch.repository.RepoPollStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaPollStateStore implements PollStateStore {

    private final RepoPollStateRepository repository;

    public JpaPollStateStore(RepoPollStateRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public List<PollTarget> claimDue(Instant now, int limit, Duration lease) {
        List<Object[]> rows = repository.lockDueKeys(now, limit);
        List<PollTarget> targets = new ArrayList<>();
        Instant leaseUntil = now.plus(lease);
        for (Object[] row : rows) {
            RepoPollStateId id = new RepoPollStateId(String.valueOf(row[0]), String.valueOf(row[1]));
            RepoPollState state = repository.findById(id).orElseThrow();
            targets.add(PollTarget.from(state));
            state.setNextPollAt(leaseUntil);
        }
        return targets;
    }

    @Override
    @Transactional
    public void saveCursors(
            String owner,
            String repo,
            Integer lastIssueNumber,
            Integer lastPrNumber,
            Instant lastPolledAt,
            Instant nextPollAt) {
        RepoPollState state = repository.findById(new RepoPollStateId(owner, repo)).orElseThrow();
        state.setLastIssueNumber(lastIssueNumber);
        state.setLastPrNumber(lastPrNumber);
        state.setLastPolledAt(lastPolledAt);
        state.setNextPollAt(nextPollAt);
    }
}
