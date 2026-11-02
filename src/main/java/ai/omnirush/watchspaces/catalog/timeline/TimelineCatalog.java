package ai.omnirush.watchspaces.catalog.timeline;

import java.util.List;

public interface TimelineCatalog {

    /**
     * Returns authored events for a title, optionally constrained to an inclusive range.
     */
    List<TimelineEvent> eventsFor(String titleId, Double fromSeconds, Double toSeconds);
}
