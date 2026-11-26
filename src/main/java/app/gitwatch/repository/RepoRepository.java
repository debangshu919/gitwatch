package app.gitwatch.repository;

import app.gitwatch.model.Repo;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoRepository extends JpaRepository<Repo, UUID> {

    Optional<Repo> findByOwnerAndName(String owner, String name);
}
