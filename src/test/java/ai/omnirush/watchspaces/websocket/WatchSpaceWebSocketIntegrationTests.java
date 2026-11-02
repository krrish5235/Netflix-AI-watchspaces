package ai.omnirush.watchspaces.websocket;

import ai.omnirush.watchspaces.api.auth.AuthResponse;
import ai.omnirush.watchspaces.api.auth.RegisterRequest;
import ai.omnirush.watchspaces.api.watchspace.CreateWatchSpaceRequest;
import ai.omnirush.watchspaces.api.watchspace.WatchSpaceResponse;
import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=test-secret-that-is-long-enough-for-hmac")
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class WatchSpaceWebSocketIntegrationTests {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final List<UUID> rooms = new ArrayList<>();

    @LocalServerPort
    private int port;
    @Autowired
    private WebTestClient rest;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private RoomRegistry registry;

    @AfterEach
    void socketsAreCleanedUpWithoutNettyLifecycleErrors(CapturedOutput output) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            for (UUID room : rooms) {
                assertThat(registry.connections(room)).isEmpty();
            }
        });
        assertThat(output.getAll()).doesNotContain("IllegalReferenceCountException", "LEAK:", "onErrorDropped");
    }

    @Test
    void authenticatedClientsJoinChatAndForwardPlaybackCommandsOverRealSockets() throws Exception {
        AuthResponse host = register();
        AuthResponse viewer = register();
        UUID room = create(host);
        try (Socket hostSocket = connect(room, host); Socket viewerSocket = connect(room, viewer)) {
            hostSocket.join();
            viewerSocket.send("{\"type\":\"chat\",\"requestId\":\"early\",\"message\":\"hello\"}");
            assertThat(viewerSocket.await("error", "early").path("code").asText()).isEqualTo("NOT_JOINED");
            JsonNode joined = viewerSocket.join();
            assertThat(joined.path("participants").toString())
                    .contains(host.user().id().toString(), viewer.user().id().toString());
            assertThat(hostSocket.await("member_joined", null).path("userId").asText())
                    .isEqualTo(viewer.user().id().toString());

            viewerSocket.send("{\"type\":\"chat\",\"requestId\":\"chat\",\"message\":\"hello\"}");
            assertThat(hostSocket.await("chat", "chat").path("message").asText()).isEqualTo("hello");
            viewerSocket.await("chat", "chat");
            for (String command : List.of("play", "pause", "seek")) {
                hostSocket.send("{\"type\":\"" + command + "\",\"requestId\":\"" + command
                        + "\",\"positionSeconds\":42.5}");
                hostSocket.await("playback", command);
                JsonNode event = viewerSocket.await("playback", command);
                assertThat(event.path("command").asText()).isEqualTo(command);
                assertThat(event.path("userId").asText()).isEqualTo(host.user().id().toString());
                if (command.equals("seek")) {
                    assertThat(event.path("positionSeconds").asDouble()).isEqualTo(42.5);
                }
            }

            // Burst messages cross the async service boundary and exercise pooled Netty payload lifetime.
            for (int index = 0; index < 40; index++) {
                viewerSocket.send("{\"type\":\"ping\",\"requestId\":\"ping-" + index + "\"}");
            }
            for (int index = 0; index < 40; index++) {
                viewerSocket.await("pong", "ping-" + index);
            }
            viewerSocket.webSocket.sendBinary(ByteBuffer.wrap(new byte[]{1, 2, 3}), true)
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(viewerSocket.await("error", null).path("code").asText()).isEqualTo("INVALID_MESSAGE");
            viewerSocket.send("{bad json");
            assertThat(viewerSocket.await("error", null).path("code").asText()).isEqualTo("INVALID_MESSAGE");
            viewerSocket.send("{\"type\":\"leave\",\"requestId\":\"leave\"}");
            viewerSocket.await("left", "leave");
            hostSocket.await("member_left", null);
        }
    }

    @Test
    void requiresAValidAccessTokenInTheBearerHeaderOnly() throws Exception {
        AuthResponse host = register();
        UUID room = create(host);
        rejected(room, null, "");
        rejected(room, "not-a-jwt", "");
        rejected(room, host.refreshToken(), "");
        JwtService expiredTokens = new JwtService("test-secret-that-is-long-enough-for-hmac", -1, 7);
        rejected(room, expiredTokens.createAccessToken(host.user().id(), host.user().role()), "");
        for (String parameter : List.of("access_token", "token")) {
            rejected(room, null, "?" + parameter + "=" + host.accessToken());
            rejected(room, "invalid", "?" + parameter + "=" + host.accessToken());
            rejected(room, host.accessToken(), "?" + parameter + "=" + host.accessToken());
        }
        try (Socket socket = connect(room, host)) {
            socket.join();
        }
    }

    @Test
    void authorizesPlaybackUsingTheCurrentHostAfterRestTransfer() throws Exception {
        AuthResponse host = register();
        AuthResponse viewer = register();
        UUID room = create(host);
        try (Socket oldHost = connect(room, host); Socket newHost = connect(room, viewer)) {
            oldHost.join();
            newHost.join();
            for (String command : List.of("play", "pause", "seek")) {
                newHost.send("{\"type\":\"" + command + "\",\"requestId\":\"denied-" + command
                        + "\",\"positionSeconds\":1}");
                assertThat(newHost.await("error", "denied-" + command).path("code").asText())
                        .isEqualTo("HOST_REQUIRED");
            }
            post(room, "transfer-host/" + viewer.user().id(), host);
            oldHost.send("{\"type\":\"play\",\"requestId\":\"old-host\"}");
            assertThat(oldHost.await("error", "old-host").path("code").asText()).isEqualTo("HOST_REQUIRED");
            newHost.send("{\"type\":\"play\",\"requestId\":\"new-host\"}");
            oldHost.await("playback", "new-host");
            newHost.await("playback", "new-host");
            assertThat(oldHost.seen).noneMatch(event -> event.path("requestId").asText().startsWith("denied-"));
            assertThat(newHost.seen).noneMatch(event -> event.path("requestId").asText().equals("old-host"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "play", "join"})
    void removedParticipantCannotSendOrUseDuplicateJoinToRestoreMembership(String type) throws Exception {
        AuthResponse host = register();
        AuthResponse viewer = register();
        UUID room = create(host);
        try (Socket hostSocket = connect(room, host); Socket removed = connect(room, viewer)) {
            hostSocket.join();
            removed.join();
            remove(room, host, viewer);
            removed.send("{\"type\":\"" + type + "\",\"requestId\":\"revoked\",\"message\":\"forbidden\"}");
            assertThat(removed.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1008);
            hostSocket.send("{\"type\":\"ping\",\"requestId\":\"barrier\"}");
            hostSocket.await("pong", "barrier");
            assertThat(hostSocket.seen).noneMatch(event -> event.path("requestId").asText().equals("revoked"));
            rest.get().uri("/api/v1/watch-spaces/{id}", room)
                    .headers(headers -> headers.setBearerAuth(viewer.accessToken()))
                    .exchange().expectStatus().isForbidden();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "leave"})
    void revokedIdleRecipientDoesNotReceiveRoomEvents(String operation) throws Exception {
        AuthResponse host = register();
        AuthResponse viewer = register();
        UUID room = create(host);
        try (Socket hostSocket = connect(room, host); Socket removed = connect(room, viewer)) {
            hostSocket.join();
            removed.join();
            if (operation.equals("remove")) {
                remove(room, host, viewer);
            } else {
                post(room, "leave", viewer);
            }
            hostSocket.send("{\"type\":\"chat\",\"requestId\":\"private\",\"message\":\"members only\"}");
            hostSocket.await("chat", "private");
            assertThat(removed.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1008);
            assertThat(removed.events).noneMatch(event -> event.path("requestId").asText().equals("private"));
        }
    }

    @Test
    void endedRoomRejectsCommandsAndNewConnections() throws Exception {
        AuthResponse host = register();
        UUID room = create(host);
        try (Socket socket = connect(room, host)) {
            socket.join();
            post(room, "end", host);
            socket.send("{\"type\":\"play\",\"requestId\":\"ended\"}");
            assertThat(socket.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1008);
            assertThat(socket.events).noneMatch(event -> event.path("type").asText().equals("playback"));
        }
        try (Socket socket = connect(room, host)) {
            assertThat(socket.closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1008);
        }
    }

    private AuthResponse register() {
        return rest.post().uri("/api/v1/auth/register")
                .bodyValue(new RegisterRequest(UUID.randomUUID() + "@example.test", "Socket User", "strong-password-123"))
                .exchange().expectStatus().isCreated().expectBody(AuthResponse.class).returnResult().getResponseBody();
    }

    private UUID create(AuthResponse host) {
        WatchSpaceResponse response = rest.post().uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(host.accessToken()))
                .bodyValue(new CreateWatchSpaceRequest("socket-title", 5, AiVerbosity.NORMAL, true))
                .exchange().expectStatus().isCreated().expectBody(WatchSpaceResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        rooms.add(response.watchSpaceId());
        return response.watchSpaceId();
    }

    private void post(UUID room, String action, AuthResponse actor) {
        rest.post().uri("/api/v1/watch-spaces/" + room + "/" + action)
                .headers(headers -> headers.setBearerAuth(actor.accessToken()))
                .exchange().expectStatus().isOk();
    }

    private void remove(UUID room, AuthResponse host, AuthResponse viewer) {
        rest.delete().uri("/api/v1/watch-spaces/{id}/participants/{participant}", room, viewer.user().id())
                .headers(headers -> headers.setBearerAuth(host.accessToken()))
                .exchange().expectStatus().isOk();
    }

    private Socket connect(UUID room, AuthResponse user) throws Exception {
        Socket socket = new Socket();
        socket.webSocket = CLIENT.newWebSocketBuilder().connectTimeout(TIMEOUT)
                .header("Authorization", "Bearer " + user.accessToken())
                .buildAsync(uri(room, ""), socket).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return socket;
    }

    private void rejected(UUID room, String token, String query) throws Exception {
        var builder = CLIENT.newWebSocketBuilder().connectTimeout(TIMEOUT);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            WebSocket socket = builder.buildAsync(uri(room, query), new Socket())
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            socket.abort();
            fail("Handshake unexpectedly accepted without a valid bearer access token");
        } catch (ExecutionException exception) {
            // Do not print the URI, headers or exception text: the negative cases contain credentials.
            assertThat(exception.getCause() instanceof WebSocketHandshakeException).isTrue();
            assertThat(((WebSocketHandshakeException) exception.getCause()).getResponse().statusCode()).isEqualTo(401);
        }
    }

    private URI uri(UUID room, String query) {
        return URI.create("ws://localhost:" + port + "/api/v1/ws/watch-spaces/" + room + query);
    }

    private final class Socket implements WebSocket.Listener, AutoCloseable {
        private final ArrayBlockingQueue<JsonNode> events = new ArrayBlockingQueue<>(128);
        private final List<JsonNode> seen = new ArrayList<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();
        private WebSocket webSocket;

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                try {
                    if (!events.offer(mapper.readTree(fragments.toString()))) {
                        closed.completeExceptionally(new AssertionError("Test receive queue overflow"));
                    }
                } catch (Exception exception) {
                    closed.completeExceptionally(exception);
                }
                fragments.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            closed.completeExceptionally(error);
        }

        private void send(String text) throws Exception {
            webSocket.sendText(text, true).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        private JsonNode join() throws Exception {
            send("{\"type\":\"join\",\"requestId\":\"join\"}");
            return await("joined", "join");
        }

        private JsonNode await(String type, String requestId) throws Exception {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode event = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertThat(event).as("Expected %s/%s; close received: %s", type, requestId, closed.isDone()).isNotNull();
                seen.add(event);
                if (event.path("type").asText().equals(type)
                        && (requestId == null || event.path("requestId").asText().equals(requestId))) {
                    return event;
                }
            }
            throw new AssertionError("Expected event was not received");
        }

        @Override
        public void close() {
            if (webSocket != null) {
                try {
                    if (!closed.isDone()) {
                        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
                        closed.get(2, TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {
                    // Always release the transport, even when an assertion failed earlier.
                } finally {
                    webSocket.abort();
                }
            }
        }
    }
}
