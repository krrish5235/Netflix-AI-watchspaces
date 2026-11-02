package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.api.watchspace.CreateWatchSpaceRequest;
import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.Role;
import ai.omnirush.watchspaces.domain.UserAccount;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;
import ai.omnirush.watchspaces.service.ConflictException;
import ai.omnirush.watchspaces.service.ForbiddenException;
import ai.omnirush.watchspaces.service.NotFoundException;
import ai.omnirush.watchspaces.service.WatchSpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "security.jwt.secret=test-secret-that-is-long-enough-for-hmac")
@ActiveProfiles("persistence-test")
class PostgresStoreTests {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAccountStore userAccountStore;

    @Autowired
    private WatchSpaceStore watchSpaceStore;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void createSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS watch_space_participants");
        jdbcTemplate.execute("DROP TABLE IF EXISTS watch_spaces");
        jdbcTemplate.execute("DROP TABLE IF EXISTS user_accounts");
        jdbcTemplate.execute("""
                CREATE TABLE user_accounts (
                    id UUID PRIMARY KEY,
                    email VARCHAR(320) NOT NULL UNIQUE,
                    display_name VARCHAR(80) NOT NULL,
                    password_hash VARCHAR(100) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE watch_spaces (
                    id UUID PRIMARY KEY,
                    title_id VARCHAR(200) NOT NULL,
                    host_user_id UUID NOT NULL REFERENCES user_accounts(id),
                    invite_code VARCHAR(32) NOT NULL UNIQUE,
                    max_participants INTEGER NOT NULL CHECK (max_participants BETWEEN 2 AND 50),
                    ai_verbosity VARCHAR(16) NOT NULL,
                    voting_enabled BOOLEAN NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    status_before_lock VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE watch_space_participants (
                    watch_space_id UUID NOT NULL REFERENCES watch_spaces(id) ON DELETE CASCADE,
                    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
                    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (watch_space_id, user_id)
                )
                """);
    }

    @Test
    void persistsAccountsAndWatchSpaceMembershipAcrossStoreReads() {
        UserAccount host = account("host@example.test", "Host");
        UserAccount participant = account("participant@example.test", "Participant");
        userAccountStore.save(host);
        userAccountStore.save(participant);

        WatchSpace room = new WatchSpace(
                UUID.randomUUID(),
                "demo-title",
                host.id(),
                "NX-ABC234",
                5,
                AiVerbosity.NORMAL,
                true,
                Instant.now());
        room.join(participant.id());
        room.start();
        watchSpaceStore.save(room);

        UserAccount loadedHost = userAccountStore.findByEmail("host@example.test").orElseThrow();
        WatchSpace loadedRoom = watchSpaceStore.findByInviteCode("NX-ABC234").orElseThrow();

        assertThat(loadedHost.id()).isEqualTo(host.id());
        assertThat(loadedRoom.status()).isEqualTo(WatchSpaceStatus.LIVE);
        assertThat(loadedRoom.hostUserId()).isEqualTo(host.id());
        assertThat(loadedRoom.participantIds()).containsExactlyInAnyOrder(host.id(), participant.id());
    }

    @Test
    void failedJoinDoesNotPublishMembershipToAnyServiceInstance() {
        WatchSpaceService service = new WatchSpaceService(watchSpaceStore);
        UUID hostId = savedUserId();
        WatchSpace room = service.create(hostId, request(3));
        UUID missingAccount = UUID.randomUUID();
        WatchSpaceService otherService = new WatchSpaceService(independentStore());
        WatchSpace before = service.require(room.id());
        otherService.require(room.id());

        assertThatThrownBy(() -> service.join(room.id(), missingAccount))
                .isInstanceOf(ConflictException.class);

        assertThat(before.participantIds()).containsExactly(hostId);
        assertThat(service.require(room.id()).participantIds()).containsExactly(hostId);
        assertThat(otherService.require(room.id()).participantIds()).containsExactly(hostId);
        assertThatThrownBy(() -> service.getForParticipant(room.id(), missingAccount))
                .isInstanceOf(ForbiddenException.class);
        UUID participantId = savedUserId();
        service.join(room.id(), participantId);
        assertThat(otherService.require(room.id()).participantIds()).containsExactlyInAnyOrder(hostId, participantId);
    }

    @Test
    void failedUpdateRollsBackRoomFieldsAndEarlierMembershipDeletes() {
        WatchSpaceService service = new WatchSpaceService(watchSpaceStore);
        UUID hostId = savedUserId();
        UUID newHostId = savedUserId();
        WatchSpace room = service.create(hostId, request(5));
        service.join(room.id(), newHostId);
        Map<UUID, Instant> originalJoinedAt = joinedAt(room.id());

        assertThatThrownBy(() -> watchSpaceStore.update(room.id(), current -> {
            current.join(UUID.randomUUID()); // Fails the participant FK after room and membership writes.
            current.transferHost(newHostId);
            current.leave(hostId);
            current.start();
            current.lock();
        })).isInstanceOf(ConflictException.class);

        WatchSpace persisted = independentStore().findById(room.id()).orElseThrow();
        assertThat(persisted.hostUserId()).isEqualTo(hostId);
        assertThat(persisted.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(persisted.statusBeforeLock()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(persisted.participantIds()).containsExactlyInAnyOrder(hostId, newHostId);
        assertThat(joinedAt(room.id())).isEqualTo(originalJoinedAt);
        assertThat(service.start(room.id(), hostId).status()).isEqualTo(WatchSpaceStatus.LIVE);
    }

    @Test
    void failedSaveRollsBackNewRoomAndInviteAndExistingRoomChanges() {
        UUID hostId = savedUserId();
        WatchSpace room = new WatchSpace(UUID.randomUUID(), "demo-title", hostId, "NX-ABC234",
                5, AiVerbosity.NORMAL, true, Instant.now());
        UUID missingAccount = UUID.randomUUID();
        room.join(missingAccount);

        assertThatThrownBy(() -> watchSpaceStore.save(room)).isInstanceOf(ConflictException.class);
        assertThat(watchSpaceStore.findById(room.id())).isEmpty();
        assertThat(watchSpaceStore.inviteCodeExists(room.inviteCode())).isFalse();
        assertThat(joinedAt(room.id())).isEmpty();

        room.leave(missingAccount);
        WatchSpace saved = watchSpaceStore.save(room);
        Map<UUID, Instant> originalJoinedAt = joinedAt(room.id());
        saved.start();
        saved.join(missingAccount);
        assertThatThrownBy(() -> watchSpaceStore.save(saved)).isInstanceOf(ConflictException.class);
        WatchSpace persisted = watchSpaceStore.findById(room.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(persisted.participantIds()).containsExactly(hostId);
        assertThat(joinedAt(room.id())).isEqualTo(originalJoinedAt);
    }

    @Test
    void mutationExceptionRollsBackAndReleasesTheRoomLock() {
        WatchSpaceService service = new WatchSpaceService(watchSpaceStore);
        UUID hostId = savedUserId();
        WatchSpace room = service.create(hostId, request(5));
        assertThatThrownBy(() -> watchSpaceStore.update(room.id(), current -> {
            current.start();
            throw new IllegalStateException("mutation failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("mutation failed");
        assertThat(service.require(room.id()).status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(new WatchSpaceService(independentStore()).start(room.id(), hostId).status())
                .isEqualTo(WatchSpaceStatus.LIVE);
    }

    @Test
    void membershipTimestampsSurviveLifecycleRejoinsTransfersAndUnrelatedRemovals() {
        WatchSpaceService service = new WatchSpaceService(watchSpaceStore);
        UUID hostId = savedUserId();
        UUID newHostId = savedUserId();
        UUID removedId = savedUserId();
        WatchSpace room = service.create(hostId, request(5));
        service.join(room.id(), newHostId);
        service.join(room.id(), removedId);
        Instant originalTime = Instant.parse("2020-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE watch_space_participants SET joined_at = ? WHERE watch_space_id = ?",
                Timestamp.from(originalTime), room.id());
        Map<UUID, Instant> originalJoinedAt = joinedAt(room.id());

        service.start(room.id(), hostId);
        service.lock(room.id(), hostId);
        service.joinByInviteCode(room.inviteCode(), newHostId);
        service.transferHost(room.id(), hostId, newHostId);
        service.unlock(room.id(), newHostId);
        assertThat(joinedAt(room.id())).isEqualTo(originalJoinedAt);

        service.leave(room.id(), hostId);
        service.removeParticipant(room.id(), newHostId, removedId);
        assertThat(joinedAt(room.id())).containsExactlyEntriesOf(Map.of(newHostId, originalTime));
        service.join(room.id(), removedId);
        assertThat(joinedAt(room.id()).get(newHostId)).isEqualTo(originalTime);
        assertThat(joinedAt(room.id()).get(removedId)).isAfter(originalTime);
    }

    @Test
    void independentStoresReloadHostTransfersLockStateAndMembershipWithDetachedResults() {
        WatchSpaceService service = new WatchSpaceService(watchSpaceStore);
        WatchSpaceService otherService = new WatchSpaceService(independentStore());
        UUID hostId = savedUserId();
        UUID newHostId = savedUserId();
        WatchSpace created = service.create(hostId, request(5));
        WatchSpace before = otherService.require(created.id());
        service.join(created.id(), newHostId);
        service.start(created.id(), hostId);
        service.lock(created.id(), hostId);
        service.transferHost(created.id(), hostId, newHostId);

        assertThatThrownBy(() -> otherService.unlock(created.id(), hostId))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        exception -> assertThat(exception.status()).isEqualTo(403));
        WatchSpace reloaded = otherService.require(created.id());
        assertThat(reloaded.hostUserId()).isEqualTo(newHostId);
        assertThat(reloaded.status()).isEqualTo(WatchSpaceStatus.LOCKED);
        assertThat(otherService.unlock(created.id(), newHostId).status()).isEqualTo(WatchSpaceStatus.LIVE);
        otherService.removeParticipant(created.id(), newHostId, hostId);
        assertThatThrownBy(() -> service.requireParticipant(created.id(), hostId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(created.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(before.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(before.participantIds()).containsExactly(hostId);
        WatchSpace loaded = watchSpaceStore.findByInviteCode(created.inviteCode()).orElseThrow();
        loaded.join(hostId);
        loaded.end();
        assertThat(service.require(created.id()).status()).isEqualTo(WatchSpaceStatus.LIVE);
        assertThat(service.require(created.id()).participantIds()).containsExactly(newHostId);
    }

    @Test
    void concurrentJoinsThroughIndependentStoresCannotExceedCapacity() throws Exception {
        WatchSpaceService service = new WatchSpaceService(watchSpaceStore);
        WatchSpaceService otherService = new WatchSpaceService(independentStore());
        UUID hostId = savedUserId();
        UUID firstId = savedUserId();
        UUID secondId = savedUserId();
        WatchSpace room = service.create(hostId, request(2));
        service.require(room.id());
        otherService.require(room.id());
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
            assertThat(independentStore().findById(room.id()).orElseThrow().participantIds())
                    .containsExactlyInAnyOrder(hostId, firstJoined ? firstId : secondId);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unknownRoomUpdateIsNotFoundAndDoesNotInvokeMutation() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> watchSpaceStore.update(id, room -> {
            throw new AssertionError("Mutation must not run for an unknown room");
        })).isInstanceOfSatisfying(NotFoundException.class,
                exception -> assertThat(exception.status()).isEqualTo(404));
        assertThat(watchSpaceStore.findById(id)).isEmpty();
    }

    private boolean joinWhenReady(WatchSpaceService service, UUID roomId, UUID participantId,
                                  CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            service.join(roomId, participantId);
            return true;
        } catch (ConflictException exception) {
            assertThat(exception.status()).isEqualTo(409);
            assertThat(exception.getMessage()).isEqualTo("Watch Space has reached its participant limit");
            return false;
        }
    }

    private WatchSpaceStore independentStore() {
        // A separate store instance with the same Spring transaction interception as production.
        return applicationContext.getAutowireCapableBeanFactory().createBean(PostgresWatchSpaceStore.class);
    }

    private UUID savedUserId() {
        UserAccount user = account(UUID.randomUUID() + "@example.test", "Participant");
        userAccountStore.save(user);
        return user.id();
    }

    private CreateWatchSpaceRequest request(int capacity) {
        return new CreateWatchSpaceRequest("demo-title", capacity, AiVerbosity.NORMAL, true);
    }

    private Map<UUID, Instant> joinedAt(UUID roomId) {
        return jdbcTemplate.query(
                "SELECT user_id, joined_at FROM watch_space_participants WHERE watch_space_id = ?",
                resultSet -> {
                    Map<UUID, Instant> timestamps = new HashMap<>();
                    while (resultSet.next()) {
                        timestamps.put(resultSet.getObject("user_id", UUID.class),
                                resultSet.getTimestamp("joined_at").toInstant());
                    }
                    return timestamps;
                }, roomId);
    }

    private UserAccount account(String email, String name) {
        return new UserAccount(
                UUID.randomUUID(), email, name, "hashed-password", Role.VIEWER, Instant.now());
    }
}
