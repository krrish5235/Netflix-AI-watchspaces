package ai.omnirush.watchspaces.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Stable server event envelope. Null fields are omitted, while field order is
 * fixed to make protocol errors and acknowledgements deterministic on the
 * wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "type", "requestId", "code", "message", "userId", "command",
        "positionSeconds", "participants", "occurredAt"
})
public record ServerEvent(
        String type,
        String requestId,
        String code,
        String message,
        UUID userId,
        String command,
        Double positionSeconds,
        List<UUID> participants,
        Instant occurredAt) {

    public static ServerEvent joined(String requestId, UUID userId, Collection<UUID> participants) {
        return new ServerEvent(
                "joined", requestId, null, null, userId, null, null,
                sortedParticipants(participants), null);
    }

    public static ServerEvent left(String requestId, UUID userId) {
        return new ServerEvent("left", requestId, null, null, userId, null, null, null, null);
    }

    public static ServerEvent memberJoined(UUID userId) {
        return new ServerEvent("member_joined", null, null, null, userId, null, null, null, null);
    }

    public static ServerEvent memberLeft(UUID userId) {
        return new ServerEvent("member_left", null, null, null, userId, null, null, null, null);
    }

    public static ServerEvent chat(String requestId, UUID userId, String message) {
        return new ServerEvent(
                "chat", requestId, null, message, userId, null, null, null, Instant.now());
    }

    public static ServerEvent pong(String requestId) {
        return new ServerEvent("pong", requestId, null, null, null, null, null, null, null);
    }

    public static ServerEvent playback(
            String requestId,
            UUID userId,
            String command,
            Double positionSeconds) {
        return new ServerEvent(
                "playback", requestId, null, null, userId, command, positionSeconds, null, Instant.now());
    }

    public static ServerEvent error(String requestId, String code, String message) {
        return new ServerEvent("error", requestId, code, message, null, null, null, null, null);
    }

    private static List<UUID> sortedParticipants(Collection<UUID> participants) {
        return participants.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }
}
