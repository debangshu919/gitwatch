package app.gitwatch.service;

import app.gitwatch.model.Subscription;
import app.gitwatch.model.SubscriptionFilters;
import app.gitwatch.repository.SubscriptionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class JpaSubscriptionLookup implements SubscriptionLookup {

    private final SubscriptionRepository repository;

    public JpaSubscriptionLookup(SubscriptionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<DeliveryTarget> findActive(String owner, String repo, String eventType) {
        List<DeliveryTarget> targets = new ArrayList<>();
        for (Subscription subscription : repository.findActiveForRepoEvent(owner, repo, eventType)) {
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
        String webhook = null;
        if (subscription.getChannel().getConfig() != null) {
            webhook = subscription.getChannel().getConfig().getWebhookUrl();
        }
        return new DeliveryTarget(
                subscription.getUser().getId(),
                subscription.getId(),
                subscription.getChannel().getId(),
                subscription.getChannel().getType(),
                subscription.getChannel().isActive(),
                webhook,
                subscription.getEventType(),
                labels);
    }
}
