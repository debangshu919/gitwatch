package app.gitwatch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class RepoPollStateId implements Serializable {

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "repo", nullable = false)
    private String repo;

    public RepoPollStateId() {
    }

    public RepoPollStateId(String owner, String repo) {
        this.owner = owner;
        this.repo = repo;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepoPollStateId that)) {
            return false;
        }
        return Objects.equals(owner, that.owner) && Objects.equals(repo, that.repo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, repo);
    }
}
