package app.gitwatch.service;

import app.gitwatch.model.Channel;
import app.gitwatch.model.ChannelConfig;
import app.gitwatch.model.User;
import app.gitwatch.repository.ChannelRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChannelService {

    private final ChannelRepository channels;

    public ChannelService(ChannelRepository channels) {
        this.channels = channels;
    }

    public Channel create(User user, String type, String name, ChannelConfig config) {
        ChannelPolicy.rejectCreate(type);
        Channel channel = new Channel();
        channel.setId(UUID.randomUUID());
        channel.setUser(user);
        channel.setType(type);
        channel.setName(name);
        channel.setConfig(config);
        channel.setActive(true);
        channel.setCreatedAt(Instant.now());
        return channels.save(channel);
    }
}
