package app.gitwatch.service;

import java.util.List;

public interface SubscriptionLookup {

    List<DeliveryTarget> findActive(String owner, String repo, String eventType);
}
