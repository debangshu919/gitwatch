package app.gitwatch.service;

import app.gitwatch.model.Channel;
import app.gitwatch.model.ChannelConfig;
import app.gitwatch.model.ChannelTypes;
import app.gitwatch.model.EventTypes;
import app.gitwatch.model.Repo;
import app.gitwatch.model.RepoPollState;
import app.gitwatch.model.RepoPollStateId;
import app.gitwatch.model.Subscription;
import app.gitwatch.model.SubscriptionFilters;
import app.gitwatch.model.User;
import app.gitwatch.repository.ChannelRepository;
import app.gitwatch.repository.RepoPollStateRepository;
import app.gitwatch.repository.RepoRepository;
import app.gitwatch.repository.SubscriptionRepository;
import app.gitwatch.repository.UserRepository;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gitwatch.seed.enabled", havingValue = "true")
public class DevSeedRunner implements ApplicationRunner {

    private final UserRepository users;
    private final ChannelRepository channels;
    private final RepoRepository repos;
    private final SubscriptionRepository subscriptions;
    private final RepoPollStateRepository pollStates;

    public DevSeedRunner(
            UserRepository users,
            ChannelRepository channels,
            RepoRepository repos,
            SubscriptionRepository subscriptions,
            RepoPollStateRepository pollStates) {
        this.users = users;
        this.channels = channels;
        this.repos = repos;
        this.subscriptions = subscriptions;
        this.pollStates = pollStates;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repos.findByOwnerAndName("apache", "iggy").isPresent()) {
            return;
        }
        User user = users.findByEmail("seed@example.invalid").orElseGet(this::newUser);
        Channel channel = new Channel();
        channel.setUser(user);
        channel.setType(ChannelTypes.DISCORD);
        channel.setName("dev discord");
        ChannelConfig config = new ChannelConfig();
        config.setWebhookUrl("https://example.invalid/discord-webhook");
        channel.setConfig(config);
        channel.setActive(true);
        channel = channels.save(channel);

        Repo repo = new Repo();
        repo.setOwner("apache");
        repo.setName("iggy");
        repo.setFullName("apache/iggy");
        repo.setActive(true);
        repo = repos.save(repo);

        subscribe(user, repo, channel, EventTypes.ISSUE_OPENED);
        subscribe(user, repo, channel, EventTypes.PR_OPENED);

        RepoPollState state = new RepoPollState();
        state.setId(new RepoPollStateId("apache", "iggy"));
        state.setNextPollAt(Instant.now());
        pollStates.save(state);
    }

    private User newUser() {
        User user = new User();
        user.setEmail("seed@example.invalid");
        user.setName("GitWatch seed");
        return users.save(user);
    }

    private void subscribe(User user, Repo repo, Channel channel, String eventType) {
        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setRepo(repo);
        subscription.setChannel(channel);
        subscription.setEventType(eventType);
        subscription.setFilters(new SubscriptionFilters());
        subscription.setActive(true);
        subscriptions.save(subscription);
    }
}
