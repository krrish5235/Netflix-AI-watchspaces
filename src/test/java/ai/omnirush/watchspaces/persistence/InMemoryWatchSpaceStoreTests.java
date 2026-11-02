package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;
import ai.omnirush.watchspaces.service.ConflictException;
import ai.omnirush.watchspaces.service.NotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryWatchSpaceStoreTests {
    private final InMemoryWatchSpaceStore store = new InMemoryWatchSpaceStore();

    @Test
    void saveReadsAndUpdateDoNotExposeCommittedObjects() {
        WatchSpace input = room("NX-ABC234");
        WatchSpace saved = store.save(input);
        input.end();
        saved.join(UUID.randomUUID());
        WatchSpace loaded = store.findByInviteCode(input.inviteCode()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(loaded.participantIds()).containsExactly(input.hostUserId());
        loaded.end();

        AtomicReference<WatchSpace> captured = new AtomicReference<>();
        WatchSpace updated = store.update(input.id(), workingCopy -> {
            captured.set(workingCopy);
            workingCopy.start();
        });
        captured.get().end();
        updated.join(UUID.randomUUID());
        WatchSpace persisted = store.findById(input.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(WatchSpaceStatus.LIVE);
        assertThat(persisted.participantIds()).containsExactly(input.hostUserId());
    }

    @Test
    void failedMutationRollsBackLifecycleHostAndMembership() {
        WatchSpace room = store.save(room("NX-ABC234"));
        UUID participantId = UUID.randomUUID();
        assertThatThrownBy(() -> store.update(room.id(), workingCopy -> {
            workingCopy.join(participantId);
            workingCopy.transferHost(participantId);
            workingCopy.start();
            workingCopy.lock();
            throw new IllegalStateException("mutation failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("mutation failed");

        WatchSpace persisted = store.findById(room.id()).orElseThrow();
        assertThat(persisted.hostUserId()).isEqualTo(room.hostUserId());
        assertThat(persisted.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(persisted.statusBeforeLock()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(persisted.participantIds()).containsExactly(room.hostUserId());
        assertThat(store.update(room.id(), WatchSpace::start).status()).isEqualTo(WatchSpaceStatus.LIVE);
    }

    @Test
    void failedSaveDoesNotPublishRoomOrChangeInviteIndex() {
        WatchSpace original = store.save(room("NX-ABC234"));
        WatchSpace duplicate = room(original.inviteCode());
        assertThatThrownBy(() -> store.save(duplicate)).isInstanceOf(ConflictException.class);
        assertThat(store.findById(duplicate.id())).isEmpty();
        assertThat(store.findByInviteCode(original.inviteCode()).orElseThrow().id()).isEqualTo(original.id());
        assertThat(store.findById(original.id()).orElseThrow().participantIds()).containsExactly(original.hostUserId());
    }

    @Test
    void unknownRoomDoesNotInvokeMutationOrCreateState() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> store.update(id, room -> {
            throw new AssertionError("Mutation must not run for an unknown room");
        })).isInstanceOfSatisfying(NotFoundException.class,
                exception -> assertThat(exception.status()).isEqualTo(404));
        assertThat(store.findById(id)).isEmpty();
    }

    private WatchSpace room(String inviteCode) {
        return new WatchSpace(UUID.randomUUID(), "demo-title", UUID.randomUUID(), inviteCode,
                5, AiVerbosity.NORMAL, true, Instant.now());
    }
}
