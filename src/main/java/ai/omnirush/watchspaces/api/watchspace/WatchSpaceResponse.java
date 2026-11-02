package ai.omnirush.watchspaces.api.watchspace;

import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record WatchSpaceResponse(
        UUID watchSpaceId,
        String titleId,
        UUID hostUserId,
        String inviteCode,
        WatchSpaceStatus status,
        int maxParticipants,
        int participantCount,
        AiVerbosity aiVerbosity,
        boolean votingEnabled,
        Instant createdAt) {

    public static WatchSpaceResponse from(WatchSpace watchSpace) {
        Set<UUID> participants = watchSpace.participantIds();
        return new WatchSpaceResponse(
                watchSpace.id(),
                watchSpace.titleId(),
                watchSpace.hostUserId(),
                watchSpace.inviteCode(),
                watchSpace.status(),
                watchSpace.maxParticipants(),
                participants.size(),
                watchSpace.aiVerbosity(),
                watchSpace.votingEnabled(),
                watchSpace.createdAt());
    }
}
