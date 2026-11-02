package ai.omnirush.watchspaces.api.catalog;

import ai.omnirush.watchspaces.catalog.timeline.TimelineEvent;

import java.util.List;

public record TimelineResponse(String titleId, List<TimelineEventResponse> events) {

    public TimelineResponse {
        events = List.copyOf(events);
    }

    public static TimelineResponse from(String titleId, List<TimelineEvent> events) {
        return new TimelineResponse(
                titleId,
                events.stream().map(TimelineEventResponse::from).toList());
    }
}
