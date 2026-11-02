package ai.omnirush.watchspaces.websocket;

import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;
import ai.omnirush.watchspaces.security.JwtService;
import ai.omnirush.watchspaces.service.ApiException;
import ai.omnirush.watchspaces.service.WatchSpaceService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Bearer-authenticated room gateway. Playback events forward host commands;
 * they do not maintain a playback clock or implement drift recovery.
 */
@Component
public final class WatchSpaceWebSocketHandler implements WebSocketHandler {
    private static final CloseStatus ROOM_UNAVAILABLE =
            CloseStatus.POLICY_VIOLATION.withReason("Room or membership is no longer available");

    private final WatchSpaceService watchSpaceService;
    private final JwtService jwtService;
    private final ClientEventDecoder decoder;
    private final ServerEventEncoder encoder;
    private final RoomRegistry roomRegistry;

    public WatchSpaceWebSocketHandler(
            WatchSpaceService watchSpaceService,
            JwtService jwtService,
            ClientEventDecoder decoder,
            ServerEventEncoder encoder,
            RoomRegistry roomRegistry) {
        this.watchSpaceService = watchSpaceService;
        this.jwtService = jwtService;
        this.decoder = decoder;
        this.encoder = encoder;
        this.roomRegistry = roomRegistry;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // Select the connection publisher while authentication still has a value.
        // A successful Mono<Void> connection is also empty, so it cannot select this fallback.
        return authenticate(session)
                .map(userId -> openRoom(session, userId))
                .switchIfEmpty(Mono.fromSupplier(() -> session.close(
                        CloseStatus.POLICY_VIOLATION.withReason("Authentication required"))))
                .flatMap(connection -> connection);
    }

    private Mono<Void> openRoom(WebSocketSession session, UUID userId) {
        Optional<UUID> roomId = roomId(session.getHandshakeInfo().getUri());
        if (roomId.isEmpty()) {
            return session.close(CloseStatus.BAD_DATA.withReason("Invalid watch-space id"));
        }
        UUID id = roomId.get();
        // Production service calls may block on JDBC. Never keep this room for later authorization.
        return Mono.fromRunnable(() -> requireActive(watchSpaceService.require(id)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> runConnection(session, id, userId)))
                .onErrorResume(ApiException.class, ignored -> session.close(ROOM_UNAVAILABLE))
                .onErrorResume(ConnectionTermination.class, exception -> session.close(exception.status));
    }

    private Mono<Void> runConnection(WebSocketSession session, UUID roomId, UUID userId) {
        RoomRegistry.RoomConnection connection = roomRegistry.register(roomId, session.getId(), userId);

        Mono<Void> inbound = session.receive()
                // Copy while Netty owns the frame, before any async boundary or buffering.
                // Reactor Netty releases the original after onNext; do not release it a second time.
                .map(message -> new IncomingMessage(message.getType(),
                        message.getType() == WebSocketMessage.Type.TEXT ? message.getPayloadAsText() : null))
                .concatMap(message -> Mono.fromRunnable(() -> processMessage(message, roomId, connection))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(error -> terminate(connection, error)), 0)
                .then(waitForCloseIfTerminating(connection));

        Mono<Void> outbound = session.send(connection.outbound()
                // Demand-driven authorization also checks events queued before a REST removal/end.
                .concatMap(message -> Mono.fromCallable(() -> delivery(roomId, connection, message))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(error -> terminate(connection, error)), 0)
                .map(session::textMessage))
                .then(waitForCloseIfTerminating(connection));
        Mono<Void> terminated = connection.termination()
                // Sink emissions are serialized under a connection lock. Close/cleanup may broadcast
                // to other connections, so never run that teardown inline while holding this lock.
                .publishOn(Schedulers.boundedElastic())
                // Write the close frame BEFORE cancelling receive/send: Netty cancellation can close
                // the transport itself, losing our policy/slow-consumer close status.
                // sendClose itself completes receive. Keep the close write alive even if transport
                // teardown cancels the handler, so Netty cannot release an in-flight close frame.
                .flatMap(status -> Mono.defer(() -> session.close(status)).cache().timeout(Duration.ofSeconds(5)))
                .onErrorResume(ignored -> Mono.empty());

        // Completion, failure, cancellation, and queue overflow all tear down both halves.
        return Mono.firstWithSignal(inbound, outbound, terminated)
                .onErrorResume(ConnectionTermination.class, exception -> session.close(exception.status))
                .onErrorResume(ignored -> session.close(CloseStatus.SERVER_ERROR.withReason("Connection failed")))
                .doFinally(ignored -> disconnect(roomId, connection));
    }

    private Mono<Void> waitForCloseIfTerminating(RoomRegistry.RoomConnection connection) {
        return Mono.defer(() -> connection.isClosed() ? Mono.never() : Mono.empty());
    }

    private <T> Mono<T> terminate(RoomRegistry.RoomConnection connection, Throwable error) {
        connection.terminate(error instanceof ConnectionTermination termination
                ? termination.status : CloseStatus.SERVER_ERROR.withReason("Connection failed"));
        // The independent termination publisher owns closing and cancellation. Do not propagate an
        // application failure into Netty send/receive, which would abort the close handshake early.
        return Mono.never();
    }

    private String delivery(UUID roomId, RoomRegistry.RoomConnection connection, RoomRegistry.OutboundMessage message) {
        if (connection.isClosed()) {
            return null;
        }
        if (message.roomEvent()) {
            if (!connection.isJoined()) {
                return null;
            }
            currentParticipant(roomId, connection.userId());
        }
        return connection.isClosed() ? null : message.payload();
    }

    private void processMessage(IncomingMessage message, UUID roomId, RoomRegistry.RoomConnection connection) {
        if (connection.isClosed()) {
            return;
        }
        // This snapshot belongs only to this command, never to the connection lifetime.
        WatchSpace current = connection.isJoined()
                ? currentParticipant(roomId, connection.userId()) : null;
        if (connection.isClosed()) {
            return;
        }
        if (message.type() != WebSocketMessage.Type.TEXT) {
            emit(connection, ServerEvent.error(null, "INVALID_MESSAGE", "Only text JSON messages are supported"));
            return;
        }
        ClientEvent event;
        try {
            event = decoder.decode(message.payload());
        } catch (ProtocolException exception) {
            emit(connection, ServerEvent.error(exception.requestId(), exception.code(), exception.getMessage()));
            return;
        }
        try {
            dispatch(event, roomId, current, connection);
        } catch (ApiException exception) {
            emit(connection, ServerEvent.error(event.requestId(),
                    exception.status() == 404 ? "ROOM_NOT_FOUND" : "ROOM_UNAVAILABLE", exception.getMessage()));
        } catch (IllegalStateException exception) {
            emit(connection, ServerEvent.error(event.requestId(), "ROOM_UNAVAILABLE", "Room is unavailable"));
        }
    }

    private void dispatch(ClientEvent event, UUID roomId, WatchSpace current, RoomRegistry.RoomConnection connection) {
        UUID userId = connection.userId();
        if (event instanceof ClientEvent.Ping ping) {
            emit(connection, ServerEvent.pong(ping.requestId()));
        } else if (event instanceof ClientEvent.Join join) {
            if (!connection.isJoined()) {
                watchSpaceService.join(roomId, userId);
            }
            currentParticipant(roomId, userId);
            if (connection.isClosed()) {
                return;
            }
            RoomRegistry.JoinResult result = roomRegistry.join(roomId, connection);
            connection.emitRoomEvent(encoder.encode(ServerEvent.joined(join.requestId(), userId, result.participants())));
            if (result.newUser()) {
                roomRegistry.broadcastExcept(roomId, connection, encoder.encode(ServerEvent.memberJoined(userId)));
            }
        } else if (event instanceof ClientEvent.Leave leave) {
            RoomRegistry.LeaveResult result = roomRegistry.leave(roomId, connection);
            if (result.userGone()) {
                roomRegistry.broadcastExcept(roomId, connection, encoder.encode(ServerEvent.memberLeft(userId)));
            }
            emit(connection, ServerEvent.left(leave.requestId(), userId));
        } else if (!connection.isJoined()) {
            emit(connection, ServerEvent.error(event.requestId(), "NOT_JOINED", "Join the room before sending this event"));
        } else if (event instanceof ClientEvent.Chat chat) {
            roomRegistry.broadcast(roomId, encoder.encode(ServerEvent.chat(chat.requestId(), userId, chat.message())));
        } else if (!current.hostUserId().equals(userId)) {
            emit(connection, ServerEvent.error(event.requestId(), "HOST_REQUIRED", "Only the room host may control playback"));
        } else if (event instanceof ClientEvent.Play play) {
            playback(roomId, userId, play.requestId(), "play", null);
        } else if (event instanceof ClientEvent.Pause pause) {
            playback(roomId, userId, pause.requestId(), "pause", null);
        } else if (event instanceof ClientEvent.Seek seek) {
            playback(roomId, userId, seek.requestId(), "seek", seek.positionSeconds());
        }
    }

    private WatchSpace currentParticipant(UUID roomId, UUID userId) {
        try {
            return requireActive(watchSpaceService.requireParticipant(roomId, userId));
        } catch (ApiException exception) {
            throw new ConnectionTermination(ROOM_UNAVAILABLE);
        }
    }

    private WatchSpace requireActive(WatchSpace room) {
        if (room.status() == WatchSpaceStatus.ENDED) {
            throw new ConnectionTermination(ROOM_UNAVAILABLE);
        }
        return room;
    }

    private void playback(UUID roomId, UUID userId, String requestId, String command, Double positionSeconds) {
        roomRegistry.broadcast(roomId, encoder.encode(ServerEvent.playback(requestId, userId, command, positionSeconds)));
    }

    private void emit(RoomRegistry.RoomConnection connection, ServerEvent event) {
        connection.emit(encoder.encode(event));
    }

    private void disconnect(UUID roomId, RoomRegistry.RoomConnection connection) {
        RoomRegistry.LeaveResult result = roomRegistry.unregister(roomId, connection);
        if (result.userGone()) {
            roomRegistry.broadcastExcept(roomId, connection, encoder.encode(ServerEvent.memberLeft(connection.userId())));
        }
    }

    private Mono<UUID> authenticate(WebSocketSession session) {
        return Mono.fromCallable(() -> {
            String authorization = session.getHandshakeInfo().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return null;
            }
            try {
                return jwtService.parseAccessToken(authorization.substring("Bearer ".length()).trim()).userId();
            } catch (RuntimeException ignored) {
                return null;
            }
        });
    }

    private Optional<UUID> roomId(URI uri) {
        String path = uri.getPath();
        try {
            return Optional.of(UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record IncomingMessage(WebSocketMessage.Type type, String payload) {
    }

    private static final class ConnectionTermination extends RuntimeException {
        private final CloseStatus status;

        private ConnectionTermination(CloseStatus status) {
            super(status.getReason());
            this.status = status;
        }
    }
}
