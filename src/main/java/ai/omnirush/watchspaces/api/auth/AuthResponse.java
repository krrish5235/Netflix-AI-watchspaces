package ai.omnirush.watchspaces.api.auth;

import ai.omnirush.watchspaces.domain.Role;

import java.time.Instant;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserSummary user) {

    public record UserSummary(
            UUID id,
            String email,
            String displayName,
            Role role,
            Instant createdAt) {
    }
}
