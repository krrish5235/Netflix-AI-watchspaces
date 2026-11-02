package ai.omnirush.watchspaces.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Serializes server events using the application's configured Jackson mapper.
 */
@Component
public final class ServerEventEncoder {
    private final ObjectMapper objectMapper;

    public ServerEventEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(ServerEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode WebSocket event", exception);
        }
    }
}
