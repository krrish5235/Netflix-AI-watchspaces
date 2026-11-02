package ai.omnirush.watchspaces.service.ai;

import java.util.List;

public record GroundedAnswer(
        String answer,
        List<GroundedCitation> citations,
        double confidence) {

    public GroundedAnswer {
        citations = List.copyOf(citations);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public static GroundedAnswer insufficientContext() {
        return new GroundedAnswer(
                "I don't have enough timeline context to answer that question.",
                List.of(),
                0.0);
    }
}
