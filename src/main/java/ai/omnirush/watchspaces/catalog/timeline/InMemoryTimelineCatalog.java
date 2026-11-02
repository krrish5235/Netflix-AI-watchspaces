package ai.omnirush.watchspaces.catalog.timeline;

import ai.omnirush.watchspaces.service.NotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small immutable catalog with deterministic demo data for the test profile.
 */
@Component
@Profile("test")
public final class InMemoryTimelineCatalog implements TimelineCatalog {
    public static final double MAX_TIMESTAMP_SECONDS = TimelineCatalogSupport.MAX_TIMESTAMP_SECONDS;

    private final Map<String, List<TimelineEvent>> eventsByTitle;

    public InMemoryTimelineCatalog() {
        this(defaultEvents());
    }

    public InMemoryTimelineCatalog(Map<String, List<TimelineEvent>> eventsByTitle) {
        Map<String, List<TimelineEvent>> immutableCopy = new HashMap<>();
        eventsByTitle.forEach((titleId, events) -> {
            if (titleId == null || titleId.isBlank()) {
                throw new IllegalArgumentException("Catalog title ids must not be blank");
            }
            List<TimelineEvent> sortedEvents = new ArrayList<>(List.copyOf(events));
            sortedEvents.sort(Comparator.comparingDouble(TimelineEvent::timestampSeconds)
                    .thenComparing(TimelineEvent::eventId));
            immutableCopy.put(titleId.trim(), List.copyOf(sortedEvents));
        });
        this.eventsByTitle = Map.copyOf(immutableCopy);
    }

    @Override
    public List<TimelineEvent> eventsFor(String titleId, Double fromSeconds, Double toSeconds) {
        String normalizedTitleId = TimelineCatalogSupport.requireTitleId(titleId);
        TimelineCatalogSupport.validateRange(fromSeconds, toSeconds);

        List<TimelineEvent> events = eventsByTitle.get(normalizedTitleId);
        if (events == null) {
            throw new NotFoundException("Title was not found");
        }

        return events.stream()
                .filter(event -> fromSeconds == null || event.timestampSeconds() >= fromSeconds)
                .filter(event -> toSeconds == null || event.timestampSeconds() <= toSeconds)
                .toList();
    }

    private static Map<String, List<TimelineEvent>> defaultEvents() {
        return Map.of(
                "demo-title",
                List.of(
                        new TimelineEvent(
                                "demo-title-opening-scene",
                                "demo-title",
                                12.0,
                                "scene",
                                "Maya enters Harbor Station after receiving an anonymous message.",
                                "Opening scene"),
                        new TimelineEvent(
                                "demo-title-rios-introduction",
                                "demo-title",
                                22.0,
                                "character",
                                "Detective Rios walks into Harbor Station; he is Maya's former partner.",
                                "Character introduction"),
                        new TimelineEvent(
                                "demo-title-clock-trivia",
                                "demo-title",
                                45.0,
                                "trivia",
                                "The Harbor Station clock stopped at 9:17 during the blackout.",
                                "Production trivia"),
                        new TimelineEvent(
                                "demo-title-blackout-glossary",
                                "demo-title",
                                75.0,
                                "glossary",
                                "The blackout refers to the citywide power failure that hid the evidence.",
                                "Story glossary")));
    }
}
