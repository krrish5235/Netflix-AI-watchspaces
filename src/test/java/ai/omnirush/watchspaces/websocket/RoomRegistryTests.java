package ai.omnirush.watchspaces.websocket;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import org.reactivestreams.Subscription;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomRegistryTests {
    private static final UUID ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final RoomRegistry registry = new RoomRegistry();

    @Test
    void tracksPresencePerUserAcrossMultipleConnections() {
        RoomRegistry.RoomConnection first = registry.register(ROOM_ID, "session-a-1", USER_A);
        RoomRegistry.RoomConnection second = registry.register(ROOM_ID, "session-a-2", USER_A);
        RoomRegistry.RoomConnection other = registry.register(ROOM_ID, "session-b", USER_B);

        assertThat(registry.join(ROOM_ID, first).newUser()).isTrue();
        assertThat(registry.join(ROOM_ID, second).newUser()).isFalse();
        assertThat(registry.join(ROOM_ID, other).participants()).containsExactly(USER_A, USER_B);

        assertThat(registry.leave(ROOM_ID, first)).isEqualTo(new RoomRegistry.LeaveResult(true, false));
        assertThat(registry.unregister(ROOM_ID, second)).isEqualTo(new RoomRegistry.LeaveResult(true, true));
    }

    @Test
    void registrationCannotBeDetachedByConcurrentLastUnregister() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 500; attempt++) {
                var old = registry.register(ROOM_ID, "old", USER_A);
                registry.join(ROOM_ID, old);
                var start = new CountDownLatch(1);
                Future<?> removal = executor.submit(() -> {
                    await(start);
                    registry.unregister(ROOM_ID, old);
                });
                Future<RoomRegistry.RoomConnection> registration = executor.submit(() -> {
                    await(start);
                    return registry.register(ROOM_ID, "new", USER_B);
                });
                start.countDown();
                var current = registration.get(5, TimeUnit.SECONDS);
                removal.get(5, TimeUnit.SECONDS);
                assertThat(registry.join(ROOM_ID, current).participants()).containsExactly(USER_B);
                assertThat(registry.connections(ROOM_ID)).containsExactly(current);
                registry.unregister(ROOM_ID, current);
                assertThat(registry.connections(ROOM_ID)).isEmpty();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleUnregisterCannotRemoveAReplacementSession() {
        var old = registry.register(ROOM_ID, "session", USER_A);
        registry.join(ROOM_ID, old);
        registry.unregister(ROOM_ID, old);
        var replacement = registry.register(ROOM_ID, "session", USER_B);
        registry.join(ROOM_ID, replacement);

        assertThat(registry.unregister(ROOM_ID, old).wasJoined()).isFalse();
        assertThat(registry.connections(ROOM_ID)).containsExactly(replacement);
        assertThat(replacement.isClosed()).isFalse();
        assertThatThrownBy(() -> registry.join(ROOM_ID, old)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(ROOM_ID, "session", USER_A)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentBroadcastsAndDirectEmissionsDoNotDropMessages() throws Exception {
        var connection = registry.register(ROOM_ID, "session", USER_A);
        registry.join(ROOM_ID, connection);
        var received = new ConcurrentLinkedQueue<String>();
        Disposable subscription = connection.outbound().subscribe(message -> received.add(message.payload()));
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<?>> producers = new ArrayList<>();
            for (int producer = 0; producer < 8; producer++) {
                int id = producer;
                producers.add(executor.submit(() -> {
                    await(start);
                    for (int index = 0; index < 200; index++) {
                        String payload = id + ":" + index;
                        if (id % 2 == 0) {
                            registry.broadcast(ROOM_ID, payload);
                        } else {
                            connection.emit(payload);
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> producer : producers) {
                producer.get(5, TimeUnit.SECONDS);
            }
            assertThat(connection.isClosed()).isFalse();
            assertThat(received).hasSize(1600).doesNotHaveDuplicates();
        } finally {
            subscription.dispose();
            executor.shutdownNow();
            registry.unregister(ROOM_ID, connection);
        }
    }

    @Test
    void slowConnectionTerminatesAtTheBoundWithoutAffectingHealthyRecipients() {
        var slow = registry.register(ROOM_ID, "slow", USER_A);
        var healthy = registry.register(ROOM_ID, "healthy", USER_B);
        registry.join(ROOM_ID, slow);
        registry.join(ROOM_ID, healthy);
        BaseSubscriber<RoomRegistry.OutboundMessage> stalled = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                // Deliberately never request a message.
            }
        };
        slow.outbound().subscribe(stalled);
        List<String> received = new ArrayList<>();
        Disposable subscription = healthy.outbound().subscribe(message -> received.add(message.payload()));
        try {
            for (int index = 0; index < RoomRegistry.OUTBOUND_CAPACITY; index++) {
                registry.broadcast(ROOM_ID, "message-" + index);
            }
            assertThat(slow.isClosed()).isFalse();
            registry.broadcast(ROOM_ID, "overflow");
            assertThat(slow.termination().block(Duration.ofSeconds(1)).getCode()).isEqualTo(1013);
            assertThat(slow.isClosed()).isTrue();
            assertThat(healthy.isClosed()).isFalse();
            assertThat(received).hasSize(RoomRegistry.OUTBOUND_CAPACITY + 1).contains("overflow");
        } finally {
            stalled.cancel();
            subscription.dispose();
            registry.unregister(ROOM_ID, slow);
            registry.unregister(ROOM_ID, healthy);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent operation did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
