package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiEnvelopes.ChannelView;
import app.gitwatch.api.ApiException;
import app.gitwatch.model.Channel;
import app.gitwatch.model.ChannelConfig;
import app.gitwatch.model.User;
import app.gitwatch.notifier.DiscordClient;
import app.gitwatch.notifier.DiscordResponse;
import app.gitwatch.repository.ChannelRepository;
import app.gitwatch.repository.UserRepository;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelApiService {

    public static final String TEST_MESSAGE = "GitWatch test notification";
    private static final Set<String> DISCORD_HOSTS = Set.of(
            "discord.com",
            "discordapp.com",
            "canary.discord.com",
            "ptb.discord.com",
            "canary.discordapp.com",
            "ptb.discordapp.com");

    private final ChannelService channels;
    private final ChannelRepository repository;
    private final UserRepository users;
    private final DiscordClient discord;

    public ChannelApiService(
            ChannelService channels,
            ChannelRepository repository,
            UserRepository users,
            DiscordClient discord) {
        this.channels = channels;
        this.repository = repository;
        this.users = users;
        this.discord = discord;
    }

    @Transactional
    public ChannelView create(User user, String type, String name, String webhookUrl) {
        if (ChannelPolicy.isTelegram(type)) {
            throw new UnsupportedChannelException("telegram channels are not supported");
        }
        if (!ChannelPolicy.isDiscord(type)) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "type must be discord");
        }
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "name is required");
        }
        requireWebhook(webhookUrl);
        ChannelConfig config = new ChannelConfig();
        config.setWebhookUrl(webhookUrl.trim());
        Channel channel = channels.create(users.getReferenceById(user.getId()), type.trim().toLowerCase(), name.trim(), config);
        return view(channel);
    }

    @Transactional(readOnly = true)
    public List<ChannelView> list(User user) {
        return repository.findByUser_IdOrderByCreatedAtDesc(user.getId()).stream().map(ChannelApiService::view).toList();
    }

    @Transactional
    public void delete(User user, UUID id) {
        Channel channel = owned(user, id);
        repository.delete(channel);
    }

    @Transactional(readOnly = true)
    public void sendTest(User user, UUID id) {
        Channel channel = owned(user, id);
        if (!ChannelPolicy.isDiscord(channel.getType())) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "type must be discord");
        }
        String webhook = channel.getConfig() == null ? null : channel.getConfig().getWebhookUrl();
        requireWebhook(webhook);
        DiscordResponse response = discord.post(webhook, TEST_MESSAGE);
        if (!response.succeeded()) {
            throw new ApiException(502, ApiCodes.DELIVERY_FAILED, "Test notification was not accepted");
        }
    }

    private Channel owned(User user, UUID id) {
        return repository.findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new ApiException(404, ApiCodes.NOT_FOUND, "Channel was not found"));
    }

    private static ChannelView view(Channel channel) {
        return new ChannelView(channel.getId().toString(), channel.getType(), channel.getName(), channel.isActive());
    }

    static void requireWebhook(String url) {
        if (url == null || url.isBlank()) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "webhook_url is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "webhook_url is not a valid URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || (port != -1 && port != 443)
                || !DISCORD_HOSTS.contains(host)
                || !path.startsWith("/api/webhooks/")
                || path.contains("..")) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "webhook_url must be a Discord webhook");
        }
    }
}
