package app.gitwatch.repository;

import app.gitwatch.model.Channel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    List<Channel> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    Optional<Channel> findByIdAndUser_Id(UUID id, UUID userId);

    void deleteByUser_Id(UUID userId);
}
