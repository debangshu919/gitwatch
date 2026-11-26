package app.gitwatch.repository;

import app.gitwatch.model.RepoWatch;
import app.gitwatch.model.RepoWatchId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepoWatchRepository extends JpaRepository<RepoWatch, RepoWatchId> {

    Optional<RepoWatch> findByUserIdAndRepoId(UUID userId, UUID repoId);

    long countByRepoId(UUID repoId);

    void deleteByUserId(UUID userId);

    void deleteByUserIdAndRepoId(UUID userId, UUID repoId);

    @Query("select w.repoId from RepoWatch w where w.userId = :userId")
    List<UUID> findRepoIdsByUserId(@Param("userId") UUID userId);

    @Query("""
            select w from RepoWatch w
            join fetch w.repo
            where w.userId = :userId
            order by w.createdAt desc
            """)
    List<RepoWatch> findWatches(@Param("userId") UUID userId);
}
