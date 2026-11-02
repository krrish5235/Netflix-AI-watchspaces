package ai.omnirush.watchspaces.catalog.timeline;

import ai.omnirush.watchspaces.service.NotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test")
public final class JdbcTimelineCatalog implements TimelineCatalog {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTimelineCatalog(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TimelineEvent> eventsFor(String titleId, Double fromSeconds, Double toSeconds) {
        String normalizedTitleId = TimelineCatalogSupport.requireTitleId(titleId);
        TimelineCatalogSupport.validateRange(fromSeconds, toSeconds);
        requireTitle(normalizedTitleId);

        StringBuilder sql = new StringBuilder("""
                SELECT event_id, title_id, timestamp_seconds, type, event_text, source_label
                FROM timeline_events
                WHERE title_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(normalizedTitleId);
        if (fromSeconds != null) {
            sql.append("AND timestamp_seconds >= ? ");
            parameters.add(fromSeconds);
        }
        if (toSeconds != null) {
            sql.append("AND timestamp_seconds <= ? ");
            parameters.add(toSeconds);
        }
        sql.append("ORDER BY timestamp_seconds ASC, event_id ASC");

        return jdbcTemplate.query(
                sql.toString(),
                (resultSet, rowNumber) -> new TimelineEvent(
                        resultSet.getString("event_id"),
                        resultSet.getString("title_id"),
                        resultSet.getDouble("timestamp_seconds"),
                        resultSet.getString("type"),
                        resultSet.getString("event_text"),
                        resultSet.getString("source_label")),
                parameters.toArray());
    }

    private void requireTitle(String titleId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM titles WHERE id = ?", Integer.class, titleId);
        if (count == null || count == 0) {
            throw new NotFoundException("Title was not found");
        }
    }
}
