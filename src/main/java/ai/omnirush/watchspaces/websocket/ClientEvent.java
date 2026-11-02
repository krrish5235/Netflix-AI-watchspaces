package ai.omnirush.watchspaces.websocket;

/**
 * Typed events accepted by the watch-space WebSocket protocol.
 *
 * <p>The wire {@code type} values are {@code join}, {@code leave},
 * {@code chat}, {@code ping}, {@code play}, {@code pause}, and {@code seek}.
 * The decoder is deliberately kept separate from these values so malformed
 * JSON never reaches the room state machine.</p>
 */
public sealed interface ClientEvent
        permits ClientEvent.Join, ClientEvent.Leave, ClientEvent.Chat,
        ClientEvent.Ping, ClientEvent.Play, ClientEvent.Pause, ClientEvent.Seek {

    String requestId();

    record Join(String requestId) implements ClientEvent {
    }

    record Leave(String requestId) implements ClientEvent {
    }

    record Chat(String requestId, String message) implements ClientEvent {
    }

    record Ping(String requestId) implements ClientEvent {
    }

    record Play(String requestId) implements ClientEvent {
    }

    record Pause(String requestId) implements ClientEvent {
    }

    record Seek(String requestId, double positionSeconds) implements ClientEvent {
    }
}
