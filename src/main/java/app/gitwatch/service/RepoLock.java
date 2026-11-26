package app.gitwatch.service;

import app.gitwatch.model.Repo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RepoLock {

    private final EntityManager entityManager;

    public RepoLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void lock(UUID repoId) {
        if (repoId == null) {
            return;
        }
        entityManager.find(Repo.class, repoId, LockModeType.PESSIMISTIC_WRITE);
    }
}
