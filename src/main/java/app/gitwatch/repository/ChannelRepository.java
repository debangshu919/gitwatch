package app.gitwatch.repository;

import app.gitwatch.model.Channel;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {}
