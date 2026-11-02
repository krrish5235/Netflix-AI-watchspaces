package ai.omnirush.watchspaces.api.ai;

import ai.omnirush.watchspaces.service.ai.GroundedCitation;

public record AiCitation(String eventId, String sourceLabel) {

    public static AiCitation from(GroundedCitation citation) {
        return new AiCitation(citation.eventId(), citation.sourceLabel());
    }
}
