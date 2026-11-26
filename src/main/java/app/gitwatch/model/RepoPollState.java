package app.gitwatch.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "repo_poll_state")
public class RepoPollState {

    @EmbeddedId
    private RepoPollStateId id;

    @Column(name = "last_issue_number")
    private Integer lastIssueNumber;

    @Column(name = "last_pr_number")
    private Integer lastPrNumber;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "next_poll_at")
    private Instant nextPollAt;

    public RepoPollStateId getId() {
        return id;
    }

    public void setId(RepoPollStateId id) {
        this.id = id;
    }

    public Integer getLastIssueNumber() {
        return lastIssueNumber;
    }

    public void setLastIssueNumber(Integer lastIssueNumber) {
        this.lastIssueNumber = lastIssueNumber;
    }

    public Integer getLastPrNumber() {
        return lastPrNumber;
    }

    public void setLastPrNumber(Integer lastPrNumber) {
        this.lastPrNumber = lastPrNumber;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public void setLastPolledAt(Instant lastPolledAt) {
        this.lastPolledAt = lastPolledAt;
    }

    public Instant getNextPollAt() {
        return nextPollAt;
    }

    public void setNextPollAt(Instant nextPollAt) {
        this.nextPollAt = nextPollAt;
    }
}
