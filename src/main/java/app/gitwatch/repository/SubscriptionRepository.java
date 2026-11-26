package app.gitwatch.repository;

import app.gitwatch.model.Subscription;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
            @Param("owner") String owner, @Param("repo") String repo, @Param("eventType") String eventType);
}
