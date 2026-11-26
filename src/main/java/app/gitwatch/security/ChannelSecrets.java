package app.gitwatch.security;

import app.gitwatch.model.ChannelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ChannelSecrets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChannelSecrets() {}

    public static String write(ChannelConfig config) {
        if (config == null) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        if (config.getWebhookUrl() != null) {
            node.put("webhookUrl", EncryptorHolder.get().encrypt(config.getWebhookUrl()));
        }
        return node.toString();
    }

    public static ChannelConfig read(String json) {
        ChannelConfig config = new ChannelConfig();
        if (json == null || json.isBlank()) {
            return config;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode url = node.get("webhookUrl");
            if (url != null && url.isTextual()) {
                config.setWebhookUrl(EncryptorHolder.get().decrypt(url.asText()));
            }
            return config;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("channel config is not readable", ex);
        }
    }
}
