package ai.omnirush.watchspaces.websocket;

import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.Role;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;
import ai.omnirush.watchspaces.security.JwtService;
import ai.omnirush.watchspaces.service.ForbiddenException;
import ai.omnirush.watchspaces.service.WatchSpaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.publisher.TestPublisher;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WatchSpaceWebSocketHandlerTests {
    private static final UUID ROOM = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final JwtService TOKENS = new JwtService("test-secret-that-is-long-enough-for-hmac", 15, 7);

    @Test
    void authenticatedCompletionDoesNotTriggerAuthenticationFallbackAndCancelsOutbound() throws Exception {
        try (Fixture fixture = new Fixture(HOST, true)) {
            fixture.start();
            fixture.send("{\"type\":\"join\",\"requestId\":\"join\"}");
            fixture.next("joined");
            fixture.inbound.complete();
            fixture.finished.get(5, TimeUnit.SECONDS);
            fixture.assertUnregistered();
            assertThat(fixture.outboundCancelled.get()).isTrue();
            assertThat(fixture.closes).isEmpty();
        }
    }

    @Test
    void missingBearerDoesNotUseAQueryTokenOrHandshakePrincipal() throws Exception {
        try (Fixture fixture = new Fixture(HOST, true)) {
            when(fixture.session.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                    URI.create("ws://localhost/api/v1/ws/watch-spaces/" + ROOM
                            + "?access_token=" + TOKENS.createAccessToken(HOST, Role.VIEWER)),
                    new HttpHeaders(), Mono.just(() -> HOST.toString()), ""));
            fixture.handler.handle(fixture.session).block(Duration.ofSeconds(5));
            assertThat(fixture.closes).singleElement().extracting(CloseStatus::getCode).isEqualTo(1008);
            verifyNoInteractions(fixture.service);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "error", "cancel"})
    void outboundTerminationOrHandlerCancellationCancelsInboundAndUnregisters(String signal) throws Exception {
        try (Fixture fixture = new Fixture(HOST, true)) {
            when(fixture.session.send(any())).thenReturn(switch (signal) {
                case "complete" -> Mono.empty();
                case "error" -> Mono.error(new IllegalStateException("transport failure"));
                default -> Mono.never();
            });
            fixture.finished = fixture.handler.handle(fixture.session).toFuture();
            assertThat(fixture.receiving.await(5, TimeUnit.SECONDS)).isTrue();
            if (signal.equals("cancel")) {
                fixture.finished.cancel(true);
            } else {
                fixture.finished.get(5, TimeUnit.SECONDS);
            }
            fixture.inbound.assertCancelled();
            fixture.assertUnregistered();
            assertThat(fixture.closes).noneMatch(status -> "Authentication required".equals(status.getReason()));
        }
    }

    @Test
    void freshDetachedSnapshotsControlHostAuthorizationAndAllServiceCallsAreOffloaded() throws Exception {
        try (Fixture fixture = new Fixture(HOST, true)) {
            fixture.start();
            fixture.send("{\"type\":\"join\"}");
            fixture.next("joined");
            fixture.current.set(room(VIEWER, WatchSpaceStatus.LIVE, Set.of(HOST, VIEWER)));
            fixture.send("{\"type\":\"play\"}");
            assertThat(fixture.next("error").path("code").asText()).isEqualTo("HOST_REQUIRED");
            fixture.current.set(room(HOST, WatchSpaceStatus.LIVE, Set.of(HOST, VIEWER)));
            fixture.send("{\"type\":\"play\"}");
            assertThat(fixture.next("playback").path("command").asText()).isEqualTo("play");
            assertThat(fixture.serviceThreads).hasSizeGreaterThanOrEqualTo(7)
                    .allMatch(name -> name.startsWith("boundedElastic-"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"removed", "ended"})
    void queuedRoomEventsAreReauthorizedWhenDemandResumes(String change) throws Exception {
        try (Fixture fixture = new Fixture(VIEWER, false)) {
            fixture.start();
            fixture.sender.get().request(1);
            fixture.send("{\"type\":\"join\"}");
            fixture.next("joined");
            fixture.registry.broadcast(ROOM, "queued-private-event");
            fixture.current.set(change.equals("ended")
                    ? room(HOST, WatchSpaceStatus.ENDED, Set.of(HOST, VIEWER))
                    : room(HOST, WatchSpaceStatus.LIVE, Set.of(HOST)));
            fixture.sender.get().request(1);
            fixture.finished.get(5, TimeUnit.SECONDS);
            fixture.assertUnregistered();
            assertThat(fixture.received).isEmpty();
            assertThat(fixture.closes).singleElement().extracting(CloseStatus::getCode).isEqualTo(1008);
            fixture.inbound.assertCancelled();
        }
    }

    @Test
    void overflowingAnUndemandedOutboundClosesAndCancelsBothDirections() throws Exception {
        try (Fixture fixture = new Fixture(HOST, false)) {
            fixture.start();
            var connection = fixture.registry.connections(ROOM).get(0);
            for (int index = 0; index <= RoomRegistry.OUTBOUND_CAPACITY; index++) {
                connection.emit("queued-" + index);
            }
            fixture.finished.get(5, TimeUnit.SECONDS);
            fixture.assertUnregistered();
            fixture.inbound.assertCancelled();
            assertThat(fixture.outboundCancelled.get()).isTrue();
            assertThat(fixture.closes).singleElement().extracting(CloseStatus::getCode).isEqualTo(1013);
            assertThat(fixture.received).isEmpty();
        }
    }

    @Test
    void receiveCompletionDuringServerCloseDoesNotCancelTheCloseFrameWrite() throws Exception {
        try (Fixture fixture = new Fixture(HOST, true)) {
            Sinks.Empty<Void> writeFinished = Sinks.empty();
            AtomicBoolean writeCancelled = new AtomicBoolean();
            CountDownLatch writingClose = new CountDownLatch(1);
            when(fixture.session.close(any())).thenAnswer(invocation -> Mono.defer(() -> {
                fixture.inbound.complete(); // Reactor Netty sendClose discards/completes receive.
                writingClose.countDown();
                return writeFinished.asMono().doOnCancel(() -> writeCancelled.set(true));
            }));
            fixture.start();
            fixture.registry.connections(ROOM).get(0).terminate(CloseStatus.POLICY_VIOLATION);
            assertThat(writingClose.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.finished.isDone()).isFalse();
            assertThat(writeCancelled.get()).isFalse();
            writeFinished.tryEmitEmpty();
            fixture.finished.get(5, TimeUnit.SECONDS);
            fixture.assertUnregistered();
            assertThat(writeCancelled.get()).isFalse();
        }
    }

    private static WatchSpace room(UUID host, WatchSpaceStatus status, Set<UUID> members) {
        return new WatchSpace(ROOM, "title", host, "NX-TEST", 5, AiVerbosity.NORMAL, true,
                Instant.EPOCH, status, WatchSpaceStatus.LIVE, members);
    }

    private static WebSocketMessage text(String payload) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT,
                DefaultDataBufferFactory.sharedInstance.wrap(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Fixture implements AutoCloseable {
        private final WatchSpaceService service = mock(WatchSpaceService.class);
        private final WebSocketSession session = mock(WebSocketSession.class);
        private final RoomRegistry registry = new RoomRegistry();
        private final WatchSpaceWebSocketHandler handler = new WatchSpaceWebSocketHandler(service, TOKENS,
                new ClientEventDecoder(MAPPER), new ServerEventEncoder(MAPPER), registry);
        private final TestPublisher<WebSocketMessage> inbound = TestPublisher.create();
        private final AtomicReference<WatchSpace> current = new AtomicReference<>(
                room(HOST, WatchSpaceStatus.LIVE, Set.of(HOST, VIEWER)));
        private final List<String> serviceThreads = new CopyOnWriteArrayList<>();
        private final List<CloseStatus> closes = new CopyOnWriteArrayList<>();
        private final CountDownLatch receiving = new CountDownLatch(1);
        private final CountDownLatch sending = new CountDownLatch(1);
        private final AtomicReference<BaseSubscriber<WebSocketMessage>> sender = new AtomicReference<>();
        private final AtomicBoolean outboundCancelled = new AtomicBoolean();
        private final ArrayBlockingQueue<String> received = new ArrayBlockingQueue<>(128);
        private CompletableFuture<Void> finished;

        private Fixture(UUID user, boolean requestAutomatically) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(TOKENS.createAccessToken(user, Role.VIEWER));
            when(session.getId()).thenReturn("test-session");
            when(session.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                    URI.create("ws://localhost/api/v1/ws/watch-spaces/" + ROOM), headers, Mono.empty(), ""));
            when(session.receive()).thenReturn(inbound.flux().doOnSubscribe(ignored -> receiving.countDown()));
            when(session.textMessage(anyString())).thenAnswer(invocation -> text(invocation.getArgument(0)));
            when(session.close(any())).thenAnswer(invocation -> Mono.fromRunnable(() -> closes.add(invocation.getArgument(0))));
            when(service.require(ROOM)).thenAnswer(invocation -> snapshot());
            when(service.join(ROOM, user)).thenAnswer(invocation -> snapshot());
            when(service.requireParticipant(ROOM, user)).thenAnswer(invocation -> {
                WatchSpace room = snapshot();
                if (!room.hasParticipant(user)) {
                    throw new ForbiddenException("Not a member");
                }
                return room;
            });
            when(session.send(any())).thenAnswer(invocation -> {
                Publisher<WebSocketMessage> messages = invocation.getArgument(0);
                return Mono.<Void>create(completion -> {
                    BaseSubscriber<WebSocketMessage> subscriber = new BaseSubscriber<>() {
                        @Override
                        protected void hookOnSubscribe(Subscription subscription) {
                            sender.set(this);
                            sending.countDown();
                            if (requestAutomatically) {
                                requestUnbounded();
                            }
                        }

                        @Override
                        protected void hookOnNext(WebSocketMessage message) {
                            received.add(message.getPayloadAsText());
                            message.release();
                        }

                        @Override
                        protected void hookOnComplete() {
                            completion.success();
                        }

                        @Override
                        protected void hookOnError(Throwable throwable) {
                            completion.error(throwable);
                        }

                        @Override
                        protected void hookOnCancel() {
                            outboundCancelled.set(true);
                        }
                    };
                    completion.onCancel(subscriber::cancel);
                    Flux.from(messages).subscribe(subscriber);
                });
            });
        }

        private WatchSpace snapshot() {
            assertThat(Schedulers.isInNonBlockingThread()).isFalse();
            serviceThreads.add(Thread.currentThread().getName());
            WatchSpace room = current.get();
            // Returning a fresh detached object ensures tests cannot accidentally rely on the old service cache.
            return room(room.hostUserId(), room.status(), room.participantIds());
        }

        private void start() throws Exception {
            finished = handler.handle(session).subscribeOn(Schedulers.parallel()).toFuture();
            assertThat(receiving.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void send(String payload) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> inbound.assertMinRequested(1));
            inbound.next(text(payload));
        }

        private JsonNode next(String type) throws Exception {
            String payload = received.poll(5, TimeUnit.SECONDS);
            assertThat(payload).as("Expected %s", type).isNotNull();
            JsonNode event = MAPPER.readTree(payload);
            assertThat(event.path("type").asText()).isEqualTo(type);
            return event;
        }

        private void assertUnregistered() {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(registry.connections(ROOM)).isEmpty());
        }

        @Override
        public void close() {
            if (finished != null && !finished.isDone()) {
                finished.cancel(true);
            }
            assertUnregistered();
        }
    }
}
