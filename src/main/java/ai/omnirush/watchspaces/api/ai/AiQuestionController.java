package ai.omnirush.watchspaces.api.ai;

import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.service.WatchSpaceService;
import ai.omnirush.watchspaces.service.ai.GroundedQuestionService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/watch-spaces")
public class AiQuestionController {
    private final WatchSpaceService watchSpaceService;
    private final GroundedQuestionService groundedQuestionService;

    public AiQuestionController(
            WatchSpaceService watchSpaceService,
            GroundedQuestionService groundedQuestionService) {
        this.watchSpaceService = watchSpaceService;
        this.groundedQuestionService = groundedQuestionService;
    }

    @PostMapping("/{id}/ai/ask")
    public AiAnswerResponse ask(
            @PathVariable("id") UUID id,
            @Valid @RequestBody AiQuestionRequest request,
            Authentication authentication) {
        WatchSpace watchSpace = watchSpaceService.requireParticipant(id, userId(authentication));
        return AiAnswerResponse.from(groundedQuestionService.answer(
                watchSpace.titleId(),
                request.question(),
                request.currentPositionSeconds()));
    }

    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
