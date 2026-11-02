package ai.omnirush.watchspaces.service.ai;

import ai.omnirush.watchspaces.catalog.timeline.TimelineEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public final class DeterministicGroundedAnswerAdapter implements GroundedAnswerAdapter {
    private static final int MAX_CITATIONS = 3;

    @Override
    public GroundedAnswer generate(String question, List<TimelineEvent> retrievedEvents) {
        List<TimelineEvent> boundedEvents = retrievedEvents.stream()
                .limit(MAX_CITATIONS)
                .toList();
        String answer = "According to the timeline: " + boundedEvents.stream()
                .map(TimelineEvent::text)
                .collect(Collectors.joining(" "));
        List<GroundedCitation> citations = boundedEvents.stream()
                .map(event -> new GroundedCitation(event.eventId(), event.sourceLabel()))
                .toList();
        double confidence = Math.min(0.95, 0.70 + ((boundedEvents.size() - 1) * 0.10));
        return new GroundedAnswer(answer, citations, confidence);
    }
}
