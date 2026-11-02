package ai.omnirush.watchspaces.catalog.timeline;

import ai.omnirush.watchspaces.service.NotFoundException;
import ai.omnirush.watchspaces.service.ai.GroundedAnswer;
import ai.omnirush.watchspaces.service.ai.GroundedCitation;
import ai.omnirush.watchspaces.service.ai.GroundedQuestionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "security.jwt.secret=test-secret-that-is-long-enough-for-hmac",
        "spring.datasource.url=jdbc:h2:mem:timeline-catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        // H2 needs an alias for V1's PostgreSQL timestamp type.
        "spring.flyway.init-sqls=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
})
@Transactional
class JdbcTimelineCatalogTests {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TimelineCatalog timelineCatalog;

    @Autowired
    private Flyway flyway;

    @Autowired
    private GroundedQuestionService groundedQuestionService;

    @Test
    void defaultProfileSelectsExactlyOneJdbcTimelineCatalogAndReadsTheCompleteSeed() {
        assertThat(applicationContext.getEnvironment().getActiveProfiles()).isEmpty();
        assertThat(applicationContext.getBeansOfType(TimelineCatalog.class)).hasSize(1);
        assertThat(timelineCatalog).isInstanceOf(JdbcTimelineCatalog.class);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(timelineCatalog.eventsFor("demo-title", null, null))
                .hasSize(4)
                .containsExactlyElementsOf(new InMemoryTimelineCatalog().eventsFor("demo-title", null, null));
    }

    @Test
    void readsPersistedEventsAcrossCatalogInstancesInTimestampAndEventIdOrder() {
        jdbcTemplate.update("INSERT INTO titles (id) VALUES (?)", "persisted-title");
        List<TimelineEvent> events = List.of(
                event("end", 86_400.0), event("middle-b", 20.5),
                event("middle-a", 20.5), event("start", 0.0));
        for (TimelineEvent event : events) {
            jdbcTemplate.update("""
                    INSERT INTO timeline_events
                        (event_id, title_id, timestamp_seconds, type, event_text, source_label)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    event.eventId(), event.titleId(), event.timestampSeconds(),
                    event.type(), event.text(), event.sourceLabel());
        }

        TimelineCatalog newReader = new JdbcTimelineCatalog(jdbcTemplate);
        TimelineCatalog inMemory = new InMemoryTimelineCatalog(Map.of("persisted-title", events));
        assertThat(newReader.eventsFor(" persisted-title ", 0.0, 86_400.0))
                .containsExactlyElementsOf(inMemory.eventsFor("persisted-title", 0.0, 86_400.0));
        assertThat(newReader.eventsFor("persisted-title", 20.5, 20.5))
                .containsExactly(events.get(2), events.get(1));
    }

    @ParameterizedTest
    @MethodSource("ranges")
    void filtersInclusiveAndOpenEndedRangesConsistently(Double from, Double to, List<String> eventIds) {
        for (TimelineCatalog catalog : List.of(timelineCatalog, new InMemoryTimelineCatalog())) {
            assertThat(catalog.eventsFor(" demo-title ", from, to))
                    .extracting(TimelineEvent::eventId)
                    .containsExactlyElementsOf(eventIds);
        }
    }

    private static Stream<Arguments> ranges() {
        return Stream.of(
                Arguments.of(null, null, List.of("demo-title-opening-scene", "demo-title-rios-introduction",
                        "demo-title-clock-trivia", "demo-title-blackout-glossary")),
                Arguments.of(22.0, 45.0, List.of("demo-title-rios-introduction", "demo-title-clock-trivia")),
                Arguments.of(null, 22.0, List.of("demo-title-opening-scene", "demo-title-rios-introduction")),
                Arguments.of(45.0, null, List.of("demo-title-clock-trivia", "demo-title-blackout-glossary")),
                Arguments.of(22.0, 22.0, List.of("demo-title-rios-introduction")),
                Arguments.of(0.0, 0.0, List.of()),
                Arguments.of(76.0, 86_400.0, List.of()));
    }

    @ParameterizedTest
    @MethodSource("invalidRanges")
    void rejectsInvalidRangesConsistentlyBeforeLookingUpTitles(Double from, Double to, String message) {
        for (TimelineCatalog catalog : List.of(timelineCatalog, new InMemoryTimelineCatalog())) {
            for (String titleId : List.of("demo-title", "missing-title")) {
                assertThatThrownBy(() -> catalog.eventsFor(titleId, from, to))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(message);
            }
        }
    }

    private static Stream<Arguments> invalidRanges() {
        return Stream.concat(
                Stream.of(-0.1, 86_400.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                        .flatMap(value -> Stream.of(
                                Arguments.of(value, null, "from must be between 0 and 86400 seconds"),
                                Arguments.of(null, value, "to must be between 0 and 86400 seconds"))),
                Stream.of(Arguments.of(45.0, 22.0, "from must not be greater than to")));
    }

    @Test
    void rejectsBlankTitleIdsConsistently() {
        for (TimelineCatalog catalog : List.of(timelineCatalog, new InMemoryTimelineCatalog())) {
            for (String titleId : new String[] {null, "", " \t"}) {
                assertThatThrownBy(() -> catalog.eventsFor(titleId, null, null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("titleId must not be blank");
            }
        }
    }

    @Test
    void distinguishesMissingTitlesFromExistingEmptyTimelinesConsistently() {
        jdbcTemplate.update("INSERT INTO titles (id) VALUES (?)", "empty-title");
        TimelineCatalog inMemory = new InMemoryTimelineCatalog(Map.of("empty-title", List.of()));
        for (TimelineCatalog catalog : List.of(timelineCatalog, inMemory)) {
            assertThat(catalog.eventsFor("empty-title", null, null)).isEmpty();
            assertThat(catalog.eventsFor("empty-title", 0.0, 0.0)).isEmpty();
            assertThatThrownBy(() -> catalog.eventsFor("missing-title", null, null))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Title was not found");
            assertThatThrownBy(() -> catalog.eventsFor("missing-title", 0.0, 0.0))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Title was not found");
        }
    }

    @Test
    void groundsAnswersInPersistedEventsWithoutIncludingFutureEvents() {
        GroundedAnswer answer = groundedQuestionService.answer("demo-title", "Who is Detective Rios?", 22.0);

        assertThat(answer.answer()).contains("Detective Rios");
        assertThat(answer.citations()).containsExactly(
                new GroundedCitation("demo-title-rios-introduction", "Character introduction"));
        GroundedAnswer beforeIntroduction = groundedQuestionService.answer(
                "demo-title", "Who is Detective Rios?", 21.0);
        assertThat(beforeIntroduction).isEqualTo(GroundedAnswer.insufficientContext());
    }

    private TimelineEvent event(String id, double timestamp) {
        return new TimelineEvent(id, "persisted-title", timestamp, "scene", "Event " + id, "Source " + id);
    }
}
