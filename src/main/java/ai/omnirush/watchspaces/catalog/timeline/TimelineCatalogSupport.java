package ai.omnirush.watchspaces.catalog.timeline;

final class TimelineCatalogSupport {
    static final double MAX_TIMESTAMP_SECONDS = 86_400.0;

    private TimelineCatalogSupport() {
    }

    static String requireTitleId(String titleId) {
        if (titleId == null || titleId.isBlank()) {
            throw new IllegalArgumentException("titleId must not be blank");
        }
        return titleId.trim();
    }

    static void validateRange(Double fromSeconds, Double toSeconds) {
        if (fromSeconds != null && (!Double.isFinite(fromSeconds) || fromSeconds < 0
                || fromSeconds > MAX_TIMESTAMP_SECONDS)) {
            throw new IllegalArgumentException("from must be between 0 and 86400 seconds");
        }
        if (toSeconds != null && (!Double.isFinite(toSeconds) || toSeconds < 0
                || toSeconds > MAX_TIMESTAMP_SECONDS)) {
            throw new IllegalArgumentException("to must be between 0 and 86400 seconds");
        }
        if (fromSeconds != null && toSeconds != null && fromSeconds > toSeconds) {
            throw new IllegalArgumentException("from must not be greater than to");
        }
    }
}
