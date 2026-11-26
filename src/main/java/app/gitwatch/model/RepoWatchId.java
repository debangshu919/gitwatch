package app.gitwatch.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class RepoWatchId implements Serializable {

    private UUID userId;
    private UUID repoId;

    public RepoWatchId() {
    }

    public RepoWatchId(UUID userId, UUID repoId) {
        this.userId = userId;
        this.repoId = repoId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getRepoId() {
        return repoId;
    }

    public void setRepoId(UUID repoId) {
        this.repoId = repoId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepoWatchId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(repoId, that.repoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, repoId);
    }
}
