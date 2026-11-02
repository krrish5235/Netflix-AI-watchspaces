package ai.omnirush.watchspaces.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Live transport presence, independent of persisted membership. */
@Component
public final class RoomRegistry {
    static final int OUTBOUND_CAPACITY = 64;
    private final ConcurrentMap<UUID, Room> rooms = new ConcurrentHashMap<>();

    public RoomConnection register(UUID roomId, String sessionId, UUID userId) {
        RoomConnection connection = new RoomConnection(sessionId, userId);
        // All map membership changes use the same per-key compute lock, including empty-room removal.
        rooms.compute(roomId, (ignored, existing) -> {
            Room room = existing == null ? new Room() : existing;
            if (room.connections.putIfAbsent(sessionId, connection) != null) {
                throw new IllegalStateException("WebSocket session is already registered");
            }
            return room;
        });
        return connection;
    }

    public JoinResult join(UUID roomId, RoomConnection connection) {
        JoinResult[] result = new JoinResult[1];
        rooms.compute(roomId, (ignored, room) -> {
            if (room == null || room.connections.get(connection.sessionId()) != connection || connection.isClosed()) {
                throw new IllegalStateException("WebSocket room connection is no longer active");
            }
            boolean newUser = !room.hasJoinedUser(connection.userId());
            connection.joined = true;
            result[0] = new JoinResult(newUser, room.joinedUsers());
            return room;
        });
        return result[0];
    }

    public LeaveResult leave(UUID roomId, RoomConnection connection) {
        return remove(roomId, connection, false);
    }

    public LeaveResult unregister(UUID roomId, RoomConnection connection) {
        LeaveResult result = remove(roomId, connection, true);
        connection.complete();
        return result;
    }

    private LeaveResult remove(UUID roomId, RoomConnection connection, boolean unregister) {
        LeaveResult[] result = {new LeaveResult(false, false)};
        rooms.computeIfPresent(roomId, (ignored, room) -> {
            if (room.connections.get(connection.sessionId()) == connection) {
                boolean wasJoined = connection.joined;
                connection.joined = false;
                if (unregister) {
                    room.connections.remove(connection.sessionId());
                }
                result[0] = new LeaveResult(wasJoined, wasJoined && !room.hasJoinedUser(connection.userId()));
            }
            return room.connections.isEmpty() ? null : room;
        });
        return result[0];
    }

    public void broadcast(UUID roomId, String payload) {
        broadcastExcept(roomId, null, payload);
    }

    public void broadcastExcept(UUID roomId, RoomConnection excluded, String payload) {
        // Never invoke sink subscribers while holding a room/map lock.
        for (RoomConnection connection : connections(roomId)) {
            if (connection != excluded && connection.isJoined()) {
                connection.emitRoomEvent(payload);
            }
        }
    }

    List<RoomConnection> connections(UUID roomId) {
        List<RoomConnection> snapshot = new ArrayList<>();
        rooms.computeIfPresent(roomId, (ignored, room) -> {
            snapshot.addAll(room.connections.values());
            return room;
        });
        return snapshot;
    }

    public record JoinResult(boolean newUser, List<UUID> participants) {
    }

    public record LeaveResult(boolean wasJoined, boolean userGone) {
    }

    public record OutboundMessage(String payload, boolean roomEvent) {
    }

    public static final class RoomConnection {
        private final String sessionId;
        private final UUID userId;
        private final Sinks.Many<OutboundMessage> outbound = Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(OUTBOUND_CAPACITY));
        private final Sinks.One<CloseStatus> termination = Sinks.one();
        private volatile boolean joined;
        private volatile boolean closed;

        private RoomConnection(String sessionId, UUID userId) {
            this.sessionId = sessionId;
            this.userId = userId;
        }

        public String sessionId() {
            return sessionId;
        }

        public UUID userId() {
            return userId;
        }

        public boolean isJoined() {
            return joined;
        }

        public boolean isClosed() {
            return closed;
        }

        public Flux<OutboundMessage> outbound() {
            return outbound.asFlux();
        }

        public Mono<CloseStatus> termination() {
            return termination.asMono();
        }

        void emit(String payload) {
            emit(new OutboundMessage(payload, false));
        }

        void emitRoomEvent(String payload) {
            emit(new OutboundMessage(payload, true));
        }

        private synchronized void emit(OutboundMessage message) {
            if (closed) {
                return;
            }
            Sinks.EmitResult result = outbound.tryEmitNext(message);
            if (result.isFailure()) {
                // Signal independently of the full queue so a stalled sender can be cancelled immediately.
                terminate(new CloseStatus(1013, "Outbound queue limit exceeded"));
            }
        }

        synchronized void terminate(CloseStatus status) {
            if (!closed) {
                closed = true;
                termination.tryEmitValue(status);
            }
        }

        private synchronized void complete() {
            terminate(CloseStatus.NORMAL);
            outbound.tryEmitComplete();
        }
    }

    private static final class Room {
        private final Map<String, RoomConnection> connections = new HashMap<>();

        private boolean hasJoinedUser(UUID userId) {
            return connections.values().stream()
                    .anyMatch(connection -> connection.isJoined() && connection.userId().equals(userId));
        }

        private List<UUID> joinedUsers() {
            return connections.values().stream()
                    .filter(RoomConnection::isJoined)
                    .map(RoomConnection::userId)
                    .distinct()
                    .sorted(Comparator.comparing(UUID::toString))
                    .toList();
        }
    }
}
