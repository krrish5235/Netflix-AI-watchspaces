package ai.omnirush.watchspaces.websocket;

/**
 * A client message that cannot be interpreted as a valid protocol event.
 */
public final class ProtocolException extends RuntimeException {
    private final String code;
    private final String requestId;

    public ProtocolException(String code, String message, String requestId) {
        super(message);
        this.code = code;
        this.requestId = requestId;
    }

    public String code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }
}
