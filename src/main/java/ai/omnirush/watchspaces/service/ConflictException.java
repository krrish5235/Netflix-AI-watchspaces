package ai.omnirush.watchspaces.service;

public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(409, message);
    }
}
