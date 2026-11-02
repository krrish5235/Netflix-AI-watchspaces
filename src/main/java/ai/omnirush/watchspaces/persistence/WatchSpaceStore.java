package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.domain.WatchSpace;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public interface WatchSpaceStore {
    // Saves and reads return detached snapshots, never the store's mutable state.
    WatchSpace save(WatchSpace watchSpace);

    /**
     * Loads current state, applies the mutation, and commits atomically. A failed
     * mutation or save leaves the stored room unchanged; an unknown id is not found.
     */
    WatchSpace update(UUID id, Consumer<WatchSpace> mutation);

    Optional<WatchSpace> findById(UUID id);

    Optional<WatchSpace> findByInviteCode(String inviteCode);

    boolean inviteCodeExists(String inviteCode);
}
