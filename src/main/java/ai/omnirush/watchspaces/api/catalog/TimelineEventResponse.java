package ai.omnirush.watchspaces.api.catalog;

import ai.omnirush.watchspaces.catalog.timeline.TimelineEvent;

public record TimelineEventResponse(
        String eventId,
        double timestampSeconds,
        String type,
        String text,
        String sourceLabel) {

    public static TimelineEventResponse from(TimelineEvent event) {
        return new TimelineEventResponse(
                event.eventId(),
                event.timestampSeconds(),
                event.type(),
                event.text(),
                event.sourceLabel());
    }
}
