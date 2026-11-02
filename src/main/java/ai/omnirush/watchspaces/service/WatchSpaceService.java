package ai.omnirush.watchspaces.service;

import ai.omnirush.watchspaces.api.watchspace.CreateWatchSpaceRequest;
import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.persistence.WatchSpaceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class WatchSpaceService {
    private static final String INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom secureRandom = new SecureRandom();
    private final WatchSpaceStore store;

    @Autowired
    public WatchSpaceService(WatchSpaceStore store) {
        this.store = store;
    }

    public WatchSpace create(UUID hostUserId, CreateWatchSpaceRequest request) {
        String inviteCode = newInviteCode();
        WatchSpace watchSpace = new WatchSpace(
                UUID.randomUUID(),
                request.titleId().trim(),
                hostUserId,
                inviteCode,
                request.maxParticipants(),
                request.aiVerbosity() == null ? AiVerbosity.NORMAL : request.aiVerbosity(),
                request.votingEnabled(),
                Instant.now());
        return store.save(watchSpace);
    }

    public WatchSpace require(UUID id) {
        return store.findById(id).orElseThrow(() ->
                new NotFoundException("Watch Space was not found"));
    }

    public WatchSpace requireParticipant(UUID id, UUID userId) {
        WatchSpace watchSpace = require(id);
        if (!watchSpace.hasParticipant(userId)) {
            throw new ForbiddenException("You must join this Watch Space first");
        }
        return watchSpace;
    }

    public WatchSpace getForParticipant(UUID id, UUID userId) {
        return requireParticipant(id, userId);
    }

    public WatchSpace join(UUID id, UUID participantId) {
        return mutate(id, watchSpace -> watchSpace.join(participantId));
    }

    public WatchSpace start(UUID id, UUID actorId) {
        return mutateAsHost(id, actorId, WatchSpace::start);
    }

    public WatchSpace end(UUID id, UUID actorId) {
        return mutateAsHost(id, actorId, WatchSpace::end);
    }

    public WatchSpace lock(UUID id, UUID actorId) {
        return mutateAsHost(id, actorId, WatchSpace::lock);
    }

    public WatchSpace unlock(UUID id, UUID actorId) {
        return mutateAsHost(id, actorId, WatchSpace::unlock);
    }

    public WatchSpace leave(UUID id, UUID participantId) {
        return mutate(id, watchSpace -> watchSpace.leave(participantId));
    }

    public WatchSpace removeParticipant(UUID id, UUID actorId, UUID participantId) {
        return mutateAsHost(id, actorId, watchSpace -> watchSpace.removeParticipant(participantId));
    }

    public WatchSpace transferHost(UUID id, UUID actorId, UUID newHostId) {
        return mutateAsHost(id, actorId, watchSpace -> watchSpace.transferHost(newHostId));
    }

    public WatchSpace joinByInviteCode(String inviteCode, UUID participantId) {
        String normalizedInviteCode = inviteCode.trim().toUpperCase(Locale.ROOT);
        WatchSpace watchSpace = store.findByInviteCode(normalizedInviteCode).orElseThrow(() ->
                new NotFoundException("Invite code was not found"));
        return join(watchSpace.id(), participantId);
    }

    private WatchSpace mutate(UUID id, Consumer<WatchSpace> mutation) {
        return store.update(id, watchSpace -> {
            try {
                mutation.accept(watchSpace);
            } catch (IllegalStateException exception) {
                throw new ConflictException(exception.getMessage());
            }
        });
    }

    private WatchSpace mutateAsHost(UUID id, UUID actorId, Consumer<WatchSpace> mutation) {
        return mutate(id, watchSpace -> {
            if (!watchSpace.hostUserId().equals(actorId)) {
                throw new ForbiddenException("Only the Watch Space host can perform this action");
            }
            mutation.accept(watchSpace);
        });
    }

    private String newInviteCode() {
        String inviteCode;
        do {
            StringBuilder value = new StringBuilder("NX-");
            for (int index = 0; index < 6; index++) {
                value.append(INVITE_ALPHABET.charAt(secureRandom.nextInt(INVITE_ALPHABET.length())));
            }
            inviteCode = value.toString();
        } while (store.inviteCodeExists(inviteCode));
        return inviteCode;
    }
}
