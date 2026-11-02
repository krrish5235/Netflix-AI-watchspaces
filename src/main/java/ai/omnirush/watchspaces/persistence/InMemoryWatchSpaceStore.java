package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.service.ConflictException;
import ai.omnirush.watchspaces.service.NotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@Profile("test")
public final class InMemoryWatchSpaceStore implements WatchSpaceStore {
    private final Map<UUID, WatchSpace> watchSpaces = new HashMap<>();
    private final Map<String, UUID> idsByInviteCode = new HashMap<>();

    @Override
    public synchronized WatchSpace save(WatchSpace watchSpace) {
        WatchSpace snapshot = watchSpace.copy();
        UUID existing = idsByInviteCode.get(snapshot.inviteCode());
        if (existing != null && !existing.equals(snapshot.id())) {
            throw new ConflictException("Invite code already exists");
        }
        WatchSpace previous = watchSpaces.put(snapshot.id(), snapshot);
        if (previous != null && !previous.inviteCode().equals(snapshot.inviteCode())) {
            idsByInviteCode.remove(previous.inviteCode());
        }
        idsByInviteCode.put(snapshot.inviteCode(), snapshot.id());
        return snapshot.copy();
    }

    @Override
    public synchronized WatchSpace update(UUID id, Consumer<WatchSpace> mutation) {
        WatchSpace current = watchSpaces.get(id);
        if (current == null) {
            throw new NotFoundException("Watch Space was not found");
        }
        WatchSpace workingCopy = current.copy();
        mutation.accept(workingCopy);
        return save(workingCopy);
    }

    @Override
    public synchronized Optional<WatchSpace> findById(UUID id) {
        return Optional.ofNullable(watchSpaces.get(id)).map(WatchSpace::copy);
    }

    @Override
    public synchronized Optional<WatchSpace> findByInviteCode(String inviteCode) {
        UUID id = idsByInviteCode.get(inviteCode);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public synchronized boolean inviteCodeExists(String inviteCode) {
        return idsByInviteCode.containsKey(inviteCode);
    }
}
