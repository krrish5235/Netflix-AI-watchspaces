package ai.omnirush.watchspaces.service;

import ai.omnirush.watchspaces.api.watchspace.CreateWatchSpaceRequest;
import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;
import ai.omnirush.watchspaces.persistence.InMemoryWatchSpaceStore;
import ai.omnirush.watchspaces.persistence.WatchSpaceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class WatchSpaceServiceTests {
    private final InMemoryWatchSpaceStore store = new InMemoryWatchSpaceStore();
    private final WatchSpaceService service = new WatchSpaceService(store);

    @Test
    void readsAndMutationResultsAreDetachedSnapshots() {
        UUID hostId = UUID.randomUUID();
        WatchSpace created = service.create(hostId, request());
        WatchSpace loaded = service.require(created.id());
        WatchSpace started = service.start(created.id(), hostId);

        assertThat(created.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(loaded.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(started.status()).isEqualTo(WatchSpaceStatus.LIVE);
        started.end();
        loaded.join(UUID.randomUUID());
        assertThat(service.require(created.id()).status()).isEqualTo(WatchSpaceStatus.LIVE);
        assertThat(service.require(created.id()).participantIds()).containsExactly(hostId);
    }

    @Test
    void readsReloadStoreChangesInsteadOfKeepingCachedRooms() {
        UUID hostId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());
        WatchSpace replacement = new WatchSpace(
                room.id(), room.titleId(), hostId, room.inviteCode(), room.maxParticipants(),
                room.aiVerbosity(), room.votingEnabled(), room.createdAt());
        replacement.start();
        store.save(replacement);

        assertThat(service.require(room.id()).status()).isEqualTo(WatchSpaceStatus.LIVE);
    }

    @Test
    void existingMembersCanRejoinALockedRoomButNotAnEndedRoom() {
        UUID hostId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, new CreateWatchSpaceRequest("demo-title", 2, AiVerbosity.NORMAL, true));
        service.join(room.id(), participantId);
        service.lock(room.id(), hostId);

        String inviteCode = " " + room.inviteCode().toLowerCase(Locale.ROOT) + " ";
        assertThat(service.joinByInviteCode(inviteCode, participantId).participantIds())
                .containsExactlyInAnyOrder(hostId, participantId);
        assertThat(service.join(room.id(), hostId).participantIds()).containsExactlyInAnyOrder(hostId, participantId);
        assertThatThrownBy(() -> service.join(room.id(), UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
        service.end(room.id(), hostId);
        assertThatThrownBy(() -> service.join(room.id(), participantId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void hostCanRunTheRoomLifecycleAndUnlockRestoresThePreviousState() {
        UUID hostId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());

        assertThat(room.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(service.start(room.id(), hostId).status()).isEqualTo(WatchSpaceStatus.LIVE);

        assertThat(service.lock(room.id(), hostId).status()).isEqualTo(WatchSpaceStatus.LOCKED);
        assertThat(service.unlock(room.id(), hostId).status()).isEqualTo(WatchSpaceStatus.LIVE);

        assertThat(service.end(room.id(), hostId).status()).isEqualTo(WatchSpaceStatus.ENDED);
        assertThat(service.require(room.id()).status()).isEqualTo(WatchSpaceStatus.ENDED);
    }

    @Test
    void nonHostCannotControlRoomAndNonMembersCannotReadIt() {
        UUID hostId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());

        assertThatThrownBy(() -> service.start(room.id(), otherUserId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the Watch Space host can perform this action");
        assertThatThrownBy(() -> service.getForParticipant(room.id(), otherUserId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You must join this Watch Space first");
    }

    @Test
    void hostCanTransferHostingOnlyToAnExistingParticipant() {
        UUID hostId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());
        service.join(room.id(), participantId);

        assertThat(service.transferHost(room.id(), hostId, participantId).hostUserId()).isEqualTo(participantId);
        assertThat(service.require(room.id()).hostUserId()).isEqualTo(participantId);

        assertThatThrownBy(() -> service.transferHost(room.id(), participantId, strangerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The new host must already be a participant");
    }

    @Test
    void hostCannotLeaveAndAJoinedParticipantCanLeave() {
        UUID hostId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());
        service.join(room.id(), participantId);

        assertThatThrownBy(() -> service.leave(room.id(), hostId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The host must remain a participant in the Watch Space");

        assertThat(service.leave(room.id(), participantId).participantIds()).containsExactly(hostId);
        assertThat(service.require(room.id()).participantIds()).containsExactly(hostId);
        service.end(room.id(), hostId);
        assertThatThrownBy(() -> service.leave(room.id(), hostId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The host must remain a participant in the Watch Space");
    }

    @Test
    void otherServiceInstancesReloadHostTransfersMembershipAndLockState() {
        UUID hostId = UUID.randomUUID();
        UUID newHostId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());
        WatchSpaceService otherService = new WatchSpaceService(store);
        otherService.require(room.id());
        service.join(room.id(), newHostId);
        service.start(room.id(), hostId);
        service.lock(room.id(), hostId);
        service.transferHost(room.id(), hostId, newHostId);

        assertThat(otherService.require(room.id()).hostUserId()).isEqualTo(newHostId);
        List<Runnable> oldHostActions = List.of(
                () -> otherService.start(room.id(), hostId),
                () -> otherService.end(room.id(), hostId),
                () -> otherService.lock(room.id(), hostId),
                () -> otherService.unlock(room.id(), hostId),
                () -> otherService.removeParticipant(room.id(), hostId, newHostId),
                () -> otherService.transferHost(room.id(), hostId, hostId));
        for (Runnable action : oldHostActions) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(ForbiddenException.class,
                    exception -> assertThat(exception.status()).isEqualTo(403));
        }
        assertThat(otherService.unlock(room.id(), newHostId).status()).isEqualTo(WatchSpaceStatus.LIVE);
        otherService.removeParticipant(room.id(), newHostId, hostId);
        assertThatThrownBy(() -> service.getForParticipant(room.id(), hostId))
                .isInstanceOf(ForbiddenException.class);
        assertThat(service.require(room.id()).participantIds()).containsExactly(newHostId);
    }

    @Test
    void hostOwnershipIsCheckedAfterEnteringTheAtomicMutation() {
        UUID hostId = UUID.randomUUID();
        UUID newHostId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());
        service.join(room.id(), newHostId);

        WatchSpaceStore interleavedStore = mock(WatchSpaceStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            // Another operation transfers hosting before this operation obtains current state.
            store.update(room.id(), current -> current.transferHost(newHostId));
            return store.update(invocation.getArgument(0), invocation.getArgument(1));
        }).when(interleavedStore).update(eq(room.id()), any());
        WatchSpaceService delayedService = new WatchSpaceService(interleavedStore);

        assertThatThrownBy(() -> delayedService.start(room.id(), hostId))
                .isInstanceOf(ForbiddenException.class);
        assertThat(service.require(room.id()).hostUserId()).isEqualTo(newHostId);
        assertThat(service.require(room.id()).status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
    }

    @Test
    void unknownRoomsAreNotFoundForReadsAndMutations() {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        List<Runnable> actions = List.of(
                () -> service.require(id),
                () -> service.requireParticipant(id, actorId),
                () -> service.getForParticipant(id, actorId),
                () -> service.join(id, actorId),
                () -> service.joinByInviteCode("missing", actorId),
                () -> service.leave(id, actorId),
                () -> service.start(id, actorId),
                () -> service.end(id, actorId),
                () -> service.lock(id, actorId),
                () -> service.unlock(id, actorId),
                () -> service.removeParticipant(id, actorId, actorId),
                () -> service.transferHost(id, actorId, actorId));
        for (Runnable action : actions) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(NotFoundException.class,
                    exception -> assertThat(exception.status()).isEqualTo(404));
        }
    }

    @Test
    void invalidTransitionsReturnConflictAndLeavePersistedStateUnchanged() {
        UUID hostId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, request());
        assertThatThrownBy(() -> service.unlock(room.id(), hostId))
                .isInstanceOfSatisfying(ConflictException.class,
                        exception -> assertThat(exception.status()).isEqualTo(409));
        service.lock(room.id(), hostId);
        assertThatThrownBy(() -> service.start(room.id(), hostId)).isInstanceOf(ConflictException.class);
        assertThat(service.unlock(room.id(), hostId).status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        service.end(room.id(), hostId);
        assertThatThrownBy(() -> service.end(room.id(), hostId)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.transferHost(room.id(), hostId, hostId))
                .isInstanceOf(ConflictException.class);
        assertThat(service.require(room.id()).status()).isEqualTo(WatchSpaceStatus.ENDED);
    }

    @Test
    void concurrentJoinsAcrossServiceInstancesCannotExceedCapacity() throws Exception {
        UUID hostId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        WatchSpace room = service.create(hostId, new CreateWatchSpaceRequest("demo-title", 2, AiVerbosity.NORMAL, true));
        WatchSpaceService otherService = new WatchSpaceService(store);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> joinWhenReady(service, room.id(), firstId, ready, start));
            Future<Boolean> second = executor.submit(() -> joinWhenReady(otherService, room.id(), secondId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean firstJoined = first.get(10, TimeUnit.SECONDS);
            boolean secondJoined = second.get(10, TimeUnit.SECONDS);
            assertThat(firstJoined).isNotEqualTo(secondJoined);
            assertThat(service.require(room.id()).participantIds())
                    .containsExactlyInAnyOrder(hostId, firstJoined ? firstId : secondId);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean joinWhenReady(WatchSpaceService instance, UUID roomId, UUID participantId,
                                  CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            instance.join(roomId, participantId);
            return true;
        } catch (ConflictException exception) {
            assertThat(exception.status()).isEqualTo(409);
            assertThat(exception.getMessage()).isEqualTo("Watch Space has reached its participant limit");
            return false;
        }
    }

    private CreateWatchSpaceRequest request() {
        return new CreateWatchSpaceRequest("demo-title", 5, AiVerbosity.NORMAL, true);
    }
}
