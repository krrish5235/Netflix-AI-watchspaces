package ai.omnirush.watchspaces.api;

import ai.omnirush.watchspaces.api.auth.AuthResponse;
import ai.omnirush.watchspaces.api.auth.RegisterRequest;
import ai.omnirush.watchspaces.api.watchspace.CreateWatchSpaceRequest;
import ai.omnirush.watchspaces.api.watchspace.JoinByCodeRequest;
import ai.omnirush.watchspaces.api.watchspace.WatchSpaceResponse;
import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;
import ai.omnirush.watchspaces.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=test-secret-that-is-long-enough-for-hmac")
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class WatchSpaceApiIntegrationTests {
    @Autowired
    private WebTestClient webTestClient;

    @SpyBean
    private UserService userService;

    @Test
    void blockingPasswordAndStoreWorkRunsOutsideTheNettyEventLoop() {
        doAnswer(invocation -> {
            assertThat(Schedulers.isInNonBlockingThread()).isFalse();
            return invocation.callRealMethod();
        }).when(userService).register(anyString(), anyString(), anyString());

        assertThat(register("worker-check@example.test", "Worker Check")).isNotNull();
    }

    @Test
    void authenticatedUsersCanCreateJoinAndControlRoomWithHostAuthorization() {
        AuthResponse host = register("host-integration@example.test", "Host Integration");
        AuthResponse viewer = register("viewer-integration@example.test", "Viewer Integration");

        WatchSpaceResponse created = webTestClient.post()
                .uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(host.accessToken()))
                .bodyValue(new CreateWatchSpaceRequest("demo-title", 3, AiVerbosity.NORMAL, true))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(WatchSpaceResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo(WatchSpaceStatus.SCHEDULED);
        assertThat(created.participantCount()).isEqualTo(1);

        webTestClient.get()
                .uri("/api/v1/watch-spaces/{id}", created.watchSpaceId())
                .headers(headers -> headers.setBearerAuth(viewer.accessToken()))
                .exchange()
                .expectStatus().isForbidden();

        WatchSpaceResponse joined = webTestClient.post()
                .uri("/api/v1/watch-spaces/join-by-code")
                .headers(headers -> headers.setBearerAuth(viewer.accessToken()))
                .bodyValue(new JoinByCodeRequest(created.inviteCode()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(WatchSpaceResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(joined).isNotNull();
        assertThat(joined.participantCount()).isEqualTo(2);

        webTestClient.post()
                .uri("/api/v1/watch-spaces/{id}/start", created.watchSpaceId())
                .headers(headers -> headers.setBearerAuth(viewer.accessToken()))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.post()
                .uri("/api/v1/watch-spaces/{id}/start", created.watchSpaceId())
                .headers(headers -> headers.setBearerAuth(host.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(WatchSpaceResponse.class)
                .value(response -> assertThat(response.status()).isEqualTo(WatchSpaceStatus.LIVE));
    }

    private AuthResponse register(String email, String displayName) {
        return webTestClient.post()
                .uri("/api/v1/auth/register")
                .bodyValue(new RegisterRequest(email, displayName, "strong-password-123"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();
    }
}
