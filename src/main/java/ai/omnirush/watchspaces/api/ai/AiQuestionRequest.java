package ai.omnirush.watchspaces.api.ai;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiQuestionRequest(
        @NotBlank @Size(max = 500) String question,
        @DecimalMin(value = "0.0") @DecimalMax(value = "86400.0") Double currentPositionSeconds) {
}
