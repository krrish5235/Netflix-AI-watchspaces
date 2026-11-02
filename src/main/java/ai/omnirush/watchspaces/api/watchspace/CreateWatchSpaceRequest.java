package ai.omnirush.watchspaces.api.watchspace;

import ai.omnirush.watchspaces.domain.AiVerbosity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateWatchSpaceRequest(
        @NotBlank String titleId,
        @Min(2) @Max(50) int maxParticipants,
        AiVerbosity aiVerbosity,
        boolean votingEnabled) {
}
