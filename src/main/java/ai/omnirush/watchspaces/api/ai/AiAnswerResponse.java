package ai.omnirush.watchspaces.api.ai;

import ai.omnirush.watchspaces.service.ai.GroundedAnswer;

import java.time.Instant;
import java.util.List;

public record AiAnswerResponse(
        String answer,
        List<AiCitation> citations,
        double confidence,
        Instant generatedAt) {

    public AiAnswerResponse {
        citations = List.copyOf(citations);
    }

    public static AiAnswerResponse from(GroundedAnswer answer) {
        return new AiAnswerResponse(
                answer.answer(),
                answer.citations().stream().map(AiCitation::from).toList(),
                answer.confidence(),
                Instant.now());
    }
}
