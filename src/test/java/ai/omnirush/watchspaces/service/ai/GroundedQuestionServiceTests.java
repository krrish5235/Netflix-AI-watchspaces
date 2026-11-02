package ai.omnirush.watchspaces.service.ai;

import ai.omnirush.watchspaces.catalog.timeline.InMemoryTimelineCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedQuestionServiceTests {
    private final GroundedQuestionService service = new GroundedQuestionService(
            new InMemoryTimelineCatalog(),
            new DeterministicGroundedAnswerAdapter());

    @Test
    void matchingQuestionReturnsAContextOnlyAnswerAndCitations() {
        GroundedAnswer answer = service.answer("demo-title", "Who is Detective Rios?", 22.0);

        assertThat(answer.answer()).contains("Detective Rios");
        assertThat(answer.citations())
                .containsExactly(new GroundedCitation(
                        "demo-title-rios-introduction", "Character introduction"));
        assertThat(answer.confidence()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
    }

    @Test
    void questionWithoutAMatchingTimelineEventReturnsInsufficientContext() {
        GroundedAnswer answer = service.answer("demo-title", "What is the capital of France?", 22.0);

        assertThat(answer.answer()).isEqualTo(
                "I don't have enough timeline context to answer that question.");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.confidence()).isZero();
    }
}
