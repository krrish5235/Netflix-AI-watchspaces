package ai.omnirush.watchspaces.api.ai;

import ai.omnirush.watchspaces.catalog.timeline.InMemoryTimelineCatalog;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.service.ForbiddenException;
import ai.omnirush.watchspaces.service.WatchSpaceService;
import ai.omnirush.watchspaces.service.ai.DeterministicGroundedAnswerAdapter;
import ai.omnirush.watchspaces.service.ai.GroundedQuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiQuestionControllerTests {
    @Test
    void rejectsAnAiQuestionFromANonParticipantBeforeRetrieval() {
        UUID watchSpaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WatchSpaceService watchSpaceService = mock(WatchSpaceService.class);
        GroundedQuestionService questionService = new GroundedQuestionService(
                new InMemoryTimelineCatalog(),
                new DeterministicGroundedAnswerAdapter());
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(userId.toString());
        when(watchSpaceService.requireParticipant(watchSpaceId, userId))
                .thenThrow(new ForbiddenException("You must join this Watch Space first"));

        AiQuestionController controller = new AiQuestionController(watchSpaceService, questionService);

        assertThatThrownBy(() -> controller.ask(
                watchSpaceId,
                new AiQuestionRequest("Who is Detective Rios?", 22.0),
                authentication))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You must join this Watch Space first");
        verify(watchSpaceService).requireParticipant(watchSpaceId, userId);
    }
}
