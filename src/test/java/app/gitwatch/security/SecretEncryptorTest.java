package app.gitwatch.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.gitwatch.model.Channel;
import app.gitwatch.model.ChannelConfig;
import app.gitwatch.model.ChannelTypes;
import app.gitwatch.service.ChannelPolicy;
import app.gitwatch.service.UnsupportedChannelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecretEncryptorTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @BeforeEach
    void setUp() {
        EncryptorHolder.set(SecretEncryptor.fromBase64(KEY));
    }

    @Test
    void roundTripsAndDoesNotStorePlaintext() {
        SecretEncryptor encryptor = EncryptorHolder.get();
        String token = "gho_secret_token";
        String stored = encryptor.encrypt(token);
        assertTrue(stored.startsWith(SecretEncryptor.PREFIX));
        assertFalse(stored.contains(token));
        assertEquals(token, encryptor.decrypt(stored));
    }

    @Test
    void webhookUrlIsEncryptedInChannelConfig() {
        ChannelConfig config = new ChannelConfig();
        config.setWebhookUrl("https://discord.com/api/webhooks/123/secret");
        Channel channel = new Channel();
        channel.setType(ChannelTypes.DISCORD);
        channel.setConfig(config);
        assertFalse(channel.getConfigJson().contains("secret"));
        assertTrue(channel.getConfigJson().contains(SecretEncryptor.PREFIX));
        assertEquals(
                "https://discord.com/api/webhooks/123/secret",
                channel.getConfig().getWebhookUrl());
    }

    @Test
    void oauthConverterEncryptsTokens() {
        EncryptedStringConverter converter = new EncryptedStringConverter();
        String stored = converter.convertToDatabaseColumn("refresh-token");
        assertFalse(stored.contains("refresh-token"));
        assertEquals("refresh-token", converter.convertToEntityAttribute(stored));
    }

    @Test
    void plaintextAtRestIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> EncryptorHolder.get().decrypt("plaintext-token"));
    }

    @Test
    void telegramChannelCannotBeCreated() {
        assertThrows(UnsupportedChannelException.class, () -> ChannelPolicy.rejectCreate("telegram"));
        Channel channel = new Channel();
        channel.setType("telegram");
        assertThrows(UnsupportedChannelException.class, channel::rejectUnsupportedChannel);
    }
}
