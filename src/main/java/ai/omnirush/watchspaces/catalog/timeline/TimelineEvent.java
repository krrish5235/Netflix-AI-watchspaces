package ai.omnirush.watchspaces.catalog.timeline;

import java.util.Objects;

/**
 * Immutable, authored metadata attached to a point in a title timeline.
 */
public record TimelineEvent(
        String eventId,
        String titleId,
        double timestampSeconds,
        String type,
        String text,
        String sourceLabel) {

    public TimelineEvent {
        eventId = requiredText(eventId, "eventId");
        titleId = requiredText(titleId, "titleId");
        type = requiredText(type, "type");
        text = requiredText(text, "text");
        sourceLabel = requiredText(sourceLabel, "sourceLabel");
        if (!Double.isFinite(timestampSeconds) || timestampSeconds < 0) {
            throw new IllegalArgumentException("timestampSeconds must be a finite non-negative number");
        }
    }

    private static String requiredText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
