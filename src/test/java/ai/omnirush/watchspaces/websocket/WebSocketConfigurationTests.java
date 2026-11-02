package ai.omnirush.watchspaces.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "security.jwt.secret=test-secret-that-is-long-enough-for-hmac")
@ActiveProfiles("test")
class WebSocketConfigurationTests {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void exposesTheRoomHandlerThroughWebFluxWebSocketInfrastructure() {
        assertThat(applicationContext.getBeansOfType(WebSocketHandlerAdapter.class)).isNotEmpty();
        assertThat(applicationContext.getBean("watchSpaceWebSocketHandlerMapping"))
                .isInstanceOf(HandlerMapping.class);
    }
}
