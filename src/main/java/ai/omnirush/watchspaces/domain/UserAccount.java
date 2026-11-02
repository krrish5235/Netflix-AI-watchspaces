package ai.omnirush.watchspaces.domain;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String email,
        String displayName,
        String passwordHash,
        Role role,
        Instant createdAt) {
}
