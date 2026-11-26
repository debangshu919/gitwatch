package app.gitwatch.poller;

import app.gitwatch.model.GitHubEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class EventJson {

    private EventJson() {
    }

    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public static String write(ObjectMapper mapper, GitHubEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encode github event", ex);
        }
    }

    public static GitHubEvent read(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, GitHubEvent.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to decode github event", ex);
        }
    }
}
