package ai.omnirush.watchspaces.api.catalog;

import ai.omnirush.watchspaces.api.ApiExceptionHandler;
import ai.omnirush.watchspaces.catalog.timeline.InMemoryTimelineCatalog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "security.jwt.secret=test-secret-that-is-long-enough-for-hmac",
        "spring.datasource.url=jdbc:h2:mem:timeline-catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.init-sqls=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
})
class TimelineControllerTests {
    @Autowired
    private TimelineController jdbcController;

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "in-memory"})
    void returnsTheDemoTimelineWithinAnInclusiveRange(String catalog) {
        client(catalog).get()
                .uri("/api/v1/titles/demo-title/timeline?from=22&to=45")
                .exchange()
                .expectStatus().isOk()
                .expectBody(TimelineResponse.class)
                .value(response -> {
                    assertThat(response.titleId()).isEqualTo("demo-title");
                    assertThat(response.events()).extracting(TimelineEventResponse::eventId)
                            .containsExactly("demo-title-rios-introduction", "demo-title-clock-trivia");
                    assertThat(response.events().get(0).sourceLabel()).isEqualTo("Character introduction");
                    assertThat(response.events().get(0).timestampSeconds()).isEqualTo(22.0);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "in-memory"})
    void returnsTheCompleteTimelineWithoutFilters(String catalog) {
        client(catalog).get()
                .uri("/api/v1/titles/demo-title/timeline")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.events.length()").isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "in-memory"})
    void returnsAnEmptyTimelineWhenNoEventsMatch(String catalog) {
        client(catalog).get()
                .uri("/api/v1/titles/demo-title/timeline?from=0&to=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.titleId").isEqualTo("demo-title")
                .jsonPath("$.events").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "in-memory"})
    void returnsNotFoundForAnUnknownTitleEvenWithAnEmptyRange(String catalog) {
        client(catalog).get()
                .uri("/api/v1/titles/missing-title/timeline?from=0&to=0")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Title was not found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "in-memory"})
    void rejectsAnInvalidTimelineRange(String catalog) {
        client(catalog).get()
                .uri("/api/v1/titles/demo-title/timeline?from=50&to=20")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").isEqualTo("from must not be greater than to");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "in-memory"})
    void rejectsNonFiniteTimestamps(String catalog) {
        client(catalog).get()
                .uri("/api/v1/titles/demo-title/timeline?to=NaN")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").isEqualTo("to must be between 0 and 86400 seconds");
    }

    private WebTestClient client(String catalog) {
        TimelineController controller = catalog.equals("jdbc")
                ? jdbcController : new TimelineController(new InMemoryTimelineCatalog());
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
