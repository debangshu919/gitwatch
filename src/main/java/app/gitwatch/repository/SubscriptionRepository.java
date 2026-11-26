package app.gitwatch.repository;

import app.gitwatch.model.Subscription;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    @Query("""
            select s from Subscription s
            join fetch s.repo r
            join fetch s.channel c
            join fetch s.user u
            where r.owner = :owner
              and r.name = :repo
              and s.eventType = :eventType
              and s.active = true
              and r.active = true
            """)
    List<Subscription> findActiveForRepoEvent(
            @Param("owner") String owner,
            @Param("repo") String repo,
            @Param("eventType") String eventType);

    Optional<Subscription> findByIdAndUser_Id(UUID id, UUID userId);

    List<Subscription> findByUser_IdAndRepo_Id(UUID userId, UUID repoId);

    long countByRepo_Id(UUID repoId);

    void deleteByUser_Id(UUID userId);

    void deleteByUser_IdAndRepo_Id(UUID userId, UUID repoId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Subscription s
            set s.active = :active
            where s.user.id = :userId and s.repo.id = :repoId
            """)
    int updateActiveForUserAndRepo(
            @Param("userId") UUID userId,
            @Param("repoId") UUID repoId,
            @Param("active") boolean active);
}
