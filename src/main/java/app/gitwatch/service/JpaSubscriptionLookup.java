package app.gitwatch.service;

import app.gitwatch.model.ChannelConfig;
import app.gitwatch.model.Subscription;
import app.gitwatch.model.SubscriptionFilters;
import app.gitwatch.repository.SubscriptionRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
public class JpaSubscriptionLookup implements SubscriptionLookup {

    private static final Logger log = LoggerFactory.getLogger(JpaSubscriptionLookup.class);

    private final SubscriptionRepository repository;

    public JpaSubscriptionLookup(SubscriptionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<DeliveryTarget> findActive(String owner, String repo, String eventType) {
        return targetsFor(repository.findActiveForRepoEvent(owner, repo, eventType));
    }

    List<DeliveryTarget> targetsFor(List<Subscription> subscriptions) {
        List<DeliveryTarget> targets = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            targets.add(toTarget(subscription));
        }
        return targets;
    }

    private DeliveryTarget toTarget(Subscription subscription) {
        List<String> labels = List.of();
        SubscriptionFilters filters = subscription.getFilters();
        if (filters != null && filters.getLabels() != null) {
            labels = List.copyOf(filters.getLabels());
        }
        return new DeliveryTarget(
                subscription.getUser().getId(),
                subscription.getId(),
                subscription.getChannel().getId(),
                subscription.getChannel().getType(),
                subscription.getChannel().isActive(),
                webhook(subscription),
                subscription.getEventType(),
                labels);
    }

    private String webhook(Subscription subscription) {
        try {
            ChannelConfig config = subscription.getChannel().getConfig();
            if (config == null) {
                return null;
            }
            return config.getWebhookUrl();
        } catch (RuntimeException ex) {
            log.warn("channel config for subscription {} is unreadable", subscription.getId(), ex);
            return null;
        }
    }
}
