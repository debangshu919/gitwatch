package app.gitwatch.service;

import java.util.List;
import java.util.UUID;

public record DeliveryTarget(
        UUID userId,
        UUID subscriptionId,
        UUID channelId,
        String channelType,
        boolean channelActive,
        String webhookUrl,
        String eventType,
        List<String> requiredLabels) {
}
