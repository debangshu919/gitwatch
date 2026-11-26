package app.gitwatch.repository;

import app.gitwatch.model.RepoPollState;
import app.gitwatch.model.RepoPollStateId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoPollStateRepository
        extends JpaRepository<RepoPollState, RepoPollStateId>, RepoPollStateRepositoryCustom {}
