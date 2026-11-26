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

    @Column(name = "issue_page", nullable = false)
    private int issuePage = 1;

    @Column(name = "pr_page", nullable = false)
    private int prPage = 1;

    @Column(name = "issue_high_water")
    private Integer issueHighWater;

    @Column(name = "pr_high_water")
    private Integer prHighWater;

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

    public int getIssuePage() {
        return issuePage;
    }

    public void setIssuePage(int issuePage) {
        this.issuePage = issuePage;
    }

    public int getPrPage() {
        return prPage;
    }

    public void setPrPage(int prPage) {
        this.prPage = prPage;
    }

    public Integer getIssueHighWater() {
        return issueHighWater;
    }

    public void setIssueHighWater(Integer issueHighWater) {
        this.issueHighWater = issueHighWater;
    }

    public Integer getPrHighWater() {
        return prHighWater;
    }

    public void setPrHighWater(Integer prHighWater) {
        this.prHighWater = prHighWater;
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
