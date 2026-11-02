package ai.omnirush.watchspaces.api.watchspace;

import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.service.WatchSpaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/watch-spaces")
public class WatchSpaceController {
    private final WatchSpaceService watchSpaceService;

    public WatchSpaceController(WatchSpaceService watchSpaceService) {
        this.watchSpaceService = watchSpaceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchSpaceResponse create(
            @Valid @RequestBody CreateWatchSpaceRequest request,
            Authentication authentication) {
        WatchSpace watchSpace = watchSpaceService.create(userId(authentication), request);
        return WatchSpaceResponse.from(watchSpace);
    }

    @GetMapping("/{id}")
    public WatchSpaceResponse get(@PathVariable UUID id, Authentication authentication) {
        return WatchSpaceResponse.from(watchSpaceService.getForParticipant(id, userId(authentication)));
    }

    @PostMapping("/{id}/join")
    public WatchSpaceResponse join(@PathVariable UUID id, Authentication authentication) {
        return WatchSpaceResponse.from(watchSpaceService.join(id, userId(authentication)));
    }

    @PostMapping("/join-by-code")
    public WatchSpaceResponse joinByCode(
            @Valid @RequestBody JoinByCodeRequest request,
            Authentication authentication) {
        return WatchSpaceResponse.from(
                watchSpaceService.joinByInviteCode(request.inviteCode(), userId(authentication)));
    }

    @PostMapping("/{id}/start")
    public WatchSpaceResponse start(@PathVariable UUID id, Authentication authentication) {
        return WatchSpaceResponse.from(watchSpaceService.start(id, userId(authentication)));
    }

    @PostMapping("/{id}/end")
    public WatchSpaceResponse end(@PathVariable UUID id, Authentication authentication) {
        return WatchSpaceResponse.from(watchSpaceService.end(id, userId(authentication)));
    }

    @PostMapping("/{id}/lock")
    public WatchSpaceResponse lock(@PathVariable UUID id, Authentication authentication) {
        return WatchSpaceResponse.from(watchSpaceService.lock(id, userId(authentication)));
    }

    @PostMapping("/{id}/unlock")
    public WatchSpaceResponse unlock(@PathVariable UUID id, Authentication authentication) {
        return WatchSpaceResponse.from(watchSpaceService.unlock(id, userId(authentication)));
    }

    @PostMapping("/{id}/leave")
    public WatchSpaceResponse leave(@PathVariable UUID id, Authentication authentication) {
        return WatchSpaceResponse.from(watchSpaceService.leave(id, userId(authentication)));
    }

    @DeleteMapping("/{id}/participants/{participantId}")
    public WatchSpaceResponse removeParticipant(
            @PathVariable UUID id,
            @PathVariable UUID participantId,
            Authentication authentication) {
        return WatchSpaceResponse.from(
                watchSpaceService.removeParticipant(id, userId(authentication), participantId));
    }

    @PostMapping("/{id}/transfer-host/{newHostId}")
    public WatchSpaceResponse transferHost(
            @PathVariable UUID id,
            @PathVariable UUID newHostId,
            Authentication authentication) {
        return WatchSpaceResponse.from(
                watchSpaceService.transferHost(id, userId(authentication), newHostId));
    }

    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
