package ai.omnirush.watchspaces.api.watchspace;

import jakarta.validation.constraints.NotBlank;

public record JoinByCodeRequest(@NotBlank String inviteCode) {
}
