package app.gitwatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.gitwatch.model.Channel;
import app.gitwatch.model.ChannelConfig;
import app.gitwatch.model.ChannelTypes;
import app.gitwatch.model.EventTypes;
import app.gitwatch.model.Subscription;
import app.gitwatch.model.User;
import app.gitwatch.security.EncryptorHolder;
import app.gitwatch.security.SecretEncryptor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JpaSubscriptionLookupTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @BeforeEach
    void setUp() {
        EncryptorHolder.set(SecretEncryptor.fromBase64(KEY));
    }

    @Test
    void unreadableWebhookDoesNotDropOtherSubscribers() throws Exception {
        Subscription broken = subscription(plaintextChannel(), UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        Subscription healthy =
                subscription(encryptedChannel(), UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        JpaSubscriptionLookup lookup = new JpaSubscriptionLookup(null);

        List<DeliveryTarget> targets = lookup.targetsFor(List.of(broken, healthy));

        assertEquals(2, targets.size());
        assertEquals(broken.getId(), targets.get(0).subscriptionId());
        assertNull(targets.get(0).webhookUrl());
        assertEquals(healthy.getId(), targets.get(1).subscriptionId());
        assertEquals("https://discord.com/api/webhooks/1/secret", targets.get(1).webhookUrl());
    }

    private static Subscription subscription(Channel channel, UUID id) {
        User user = new User();
        user.setId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setUser(user);
        subscription.setChannel(channel);
        subscription.setEventType(EventTypes.ISSUE_OPENED);
        subscription.setActive(true);
        return subscription;
    }

    private static Channel encryptedChannel() {
        Channel channel = channel();
        ChannelConfig config = new ChannelConfig();
        config.setWebhookUrl("https://discord.com/api/webhooks/1/secret");
        channel.setConfig(config);
        return channel;
    }

    private static Channel plaintextChannel() throws Exception {
        Channel channel = channel();
        Field config = Channel.class.getDeclaredField("config");
        config.setAccessible(true);
        config.set(channel, "{\"webhookUrl\":\"https://discord.com/api/webhooks/9/plain\"}");
        return channel;
    }

    private static Channel channel() {
        Channel channel = new Channel();
        channel.setId(UUID.randomUUID());
        channel.setType(ChannelTypes.DISCORD);
        channel.setActive(true);
        return channel;
    }
}
