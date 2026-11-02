package ai.omnirush.watchspaces.websocket;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Converts the small, explicit client protocol into typed events.
 */
@Component
public final class ClientEventDecoder {
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final int MAX_CHAT_LENGTH = 2_000;
    private static final int MAX_PAYLOAD_LENGTH = 64 * 1024;

    private final ObjectMapper objectMapper;

    public ClientEventDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ClientEvent decode(String payload) {
        if (payload == null || payload.length() > MAX_PAYLOAD_LENGTH) {
            throw invalid("Message is missing or too large", null);
        }

        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(payload)) {
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw invalid("Message must contain exactly one JSON value", null);
            }
        } catch (ProtocolException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("Message must be valid JSON", null);
        }

        if (root == null || !root.isObject()) {
            throw invalid("Message must be a JSON object", null);
        }

        String requestId = requestId(root);
        JsonNode typeNode = root.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.textValue().isBlank()) {
            throw invalid("Field 'type' is required", requestId);
        }

        return switch (typeNode.textValue()) {
            case "join" -> new ClientEvent.Join(requestId);
            case "leave" -> new ClientEvent.Leave(requestId);
            case "chat" -> new ClientEvent.Chat(requestId, chatMessage(root, requestId));
            case "ping" -> new ClientEvent.Ping(requestId);
            case "play" -> new ClientEvent.Play(requestId);
            case "pause" -> new ClientEvent.Pause(requestId);
            case "seek" -> new ClientEvent.Seek(requestId, seekPosition(root, requestId));
            default -> throw new ProtocolException(
                    "UNSUPPORTED_EVENT",
                    "Unsupported event type: " + typeNode.textValue(),
                    requestId);
        };
    }

    private String requestId(JsonNode root) {
        JsonNode requestIdNode = root.get("requestId");
        if (requestIdNode == null || requestIdNode.isNull()) {
            return null;
        }
        if (!requestIdNode.isTextual()) {
            throw invalid("Field 'requestId' must be a string", null);
        }

        String requestId = requestIdNode.textValue().trim();
        if (requestId.isEmpty() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw invalid("Field 'requestId' must contain 1 to 128 characters", null);
        }
        return requestId;
    }

    private String chatMessage(JsonNode root, String requestId) {
        JsonNode messageNode = root.get("message");
        if (messageNode == null || !messageNode.isTextual()) {
            throw invalid("Field 'message' is required", requestId);
        }

        String message = messageNode.textValue().trim();
        if (message.isEmpty() || message.length() > MAX_CHAT_LENGTH) {
            throw invalid("Field 'message' must contain 1 to 2000 characters", requestId);
        }
        return message;
    }

    private double seekPosition(JsonNode root, String requestId) {
        JsonNode positionNode = root.get("positionSeconds");
        if (positionNode == null || !positionNode.isNumber()) {
            throw invalid("Field 'positionSeconds' must be a non-negative number", requestId);
        }

        double positionSeconds = positionNode.doubleValue();
        if (!Double.isFinite(positionSeconds) || positionSeconds < 0) {
            throw invalid("Field 'positionSeconds' must be a non-negative number", requestId);
        }
        return positionSeconds;
    }

    private ProtocolException invalid(String message, String requestId) {
        return new ProtocolException("INVALID_MESSAGE", message, requestId);
    }
}
