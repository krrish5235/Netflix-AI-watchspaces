package ai.omnirush.watchspaces.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WatchSpace {
    private final UUID id;
    private final String titleId;
    private volatile UUID hostUserId;
    private final String inviteCode;
    private final int maxParticipants;
    private final AiVerbosity aiVerbosity;
    private final boolean votingEnabled;
    private final Instant createdAt;
    private final Set<UUID> participantIds = ConcurrentHashMap.newKeySet();
    private volatile WatchSpaceStatus status;
    private volatile WatchSpaceStatus statusBeforeLock;

    public WatchSpace(
            UUID id,
            String titleId,
            UUID hostUserId,
            String inviteCode,
            int maxParticipants,
            AiVerbosity aiVerbosity,
            boolean votingEnabled,
            Instant createdAt) {
        this(
                id,
                titleId,
                hostUserId,
                inviteCode,
                maxParticipants,
                aiVerbosity,
                votingEnabled,
                createdAt,
                WatchSpaceStatus.SCHEDULED,
                WatchSpaceStatus.SCHEDULED,
                Set.of(hostUserId));
    }

    public WatchSpace(
            UUID id,
            String titleId,
            UUID hostUserId,
            String inviteCode,
            int maxParticipants,
            AiVerbosity aiVerbosity,
            boolean votingEnabled,
            Instant createdAt,
            WatchSpaceStatus status,
            WatchSpaceStatus statusBeforeLock,
            Collection<UUID> participantIds) {
        this.id = id;
        this.titleId = titleId;
        this.hostUserId = hostUserId;
        this.inviteCode = inviteCode;
        this.maxParticipants = maxParticipants;
        this.aiVerbosity = aiVerbosity;
        this.votingEnabled = votingEnabled;
        this.createdAt = createdAt;
        this.status = status;
        this.statusBeforeLock = statusBeforeLock;
        this.participantIds.addAll(participantIds);
        this.participantIds.add(hostUserId);
    }

    public UUID id() {
        return id;
    }

    public String titleId() {
        return titleId;
    }

    public UUID hostUserId() {
        return hostUserId;
    }

    public String inviteCode() {
        return inviteCode;
    }

    public int maxParticipants() {
        return maxParticipants;
    }

    public AiVerbosity aiVerbosity() {
        return aiVerbosity;
    }

    public boolean votingEnabled() {
        return votingEnabled;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public WatchSpaceStatus status() {
        return status;
    }

    public WatchSpaceStatus statusBeforeLock() {
        return statusBeforeLock;
    }

    public WatchSpace copy() {
        return new WatchSpace(
                id, titleId, hostUserId, inviteCode, maxParticipants, aiVerbosity,
                votingEnabled, createdAt, status, statusBeforeLock, participantIds);
    }

    public void join(UUID userId) {
        if (status == WatchSpaceStatus.ENDED) {
            throw new IllegalStateException("Watch Space is not accepting participants");
        }
        if (participantIds.contains(userId)) {
            return;
        }
        if (status == WatchSpaceStatus.LOCKED) {
            throw new IllegalStateException("Watch Space is not accepting participants");
        }
        if (participantIds.size() >= maxParticipants) {
            throw new IllegalStateException("Watch Space has reached its participant limit");
        }
        participantIds.add(userId);
    }

    public boolean hasParticipant(UUID userId) {
        return participantIds.contains(userId);
    }

    public void leave(UUID userId) {
        if (userId.equals(hostUserId)) {
            throw new IllegalStateException("The host must remain a participant in the Watch Space");
        }
        if (!participantIds.remove(userId)) {
            throw new IllegalStateException("User is not a participant in this Watch Space");
        }
    }

    public void removeParticipant(UUID userId) {
        leave(userId);
    }

    public void transferHost(UUID newHostUserId) {
        if (!participantIds.contains(newHostUserId)) {
            throw new IllegalStateException("The new host must already be a participant");
        }
        if (status == WatchSpaceStatus.ENDED) {
            throw new IllegalStateException("An ended Watch Space cannot transfer hosting");
        }
        hostUserId = newHostUserId;
    }

    public void start() {
        if (status != WatchSpaceStatus.SCHEDULED) {
            throw new IllegalStateException("Only a scheduled Watch Space can be started");
        }
        status = WatchSpaceStatus.LIVE;
    }

    public void end() {
        if (status == WatchSpaceStatus.ENDED) {
            throw new IllegalStateException("Watch Space has already ended");
        }
        status = WatchSpaceStatus.ENDED;
    }

    public void lock() {
        if (status != WatchSpaceStatus.SCHEDULED && status != WatchSpaceStatus.LIVE) {
            throw new IllegalStateException("Only an active Watch Space can be locked");
        }
        statusBeforeLock = status;
        status = WatchSpaceStatus.LOCKED;
    }

    public void unlock() {
        if (status != WatchSpaceStatus.LOCKED) {
            throw new IllegalStateException("Watch Space is not locked");
        }
        status = statusBeforeLock;
    }

    public Set<UUID> participantIds() {
        return Set.copyOf(participantIds);
    }
}
