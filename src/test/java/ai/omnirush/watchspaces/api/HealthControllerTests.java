package ai.omnirush.watchspaces.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthControllerTests {

    private final HealthController controller = new HealthController();

    @Test
    void reportsHealthyService() {
        HealthController.HealthResponse response = controller.health();

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.service()).isEqualTo("social-watch-space-api");
        assertThat(response.timestamp()).isNotNull();
    }
}
