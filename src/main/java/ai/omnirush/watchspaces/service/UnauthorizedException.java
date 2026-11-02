package ai.omnirush.watchspaces.service;

public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(401, message);
    }
}
