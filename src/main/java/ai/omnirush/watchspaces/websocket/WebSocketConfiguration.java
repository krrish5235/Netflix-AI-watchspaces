package ai.omnirush.watchspaces.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

import java.util.Map;

/**
 * Exposes the handler through the framework's WebSocketHandlerAdapter at a
 * stable, versioned URL. Spring WebFlux supplies the adapter itself; this
 * mapping supplies the WebSocketHandler it adapts.
 */
@Configuration
public class WebSocketConfiguration {
    @Bean
    HandlerMapping watchSpaceWebSocketHandlerMapping(WatchSpaceWebSocketHandler handler) {
        return new SimpleUrlHandlerMapping(
                Map.of("/api/v1/ws/watch-spaces/{id}", handler),
                -1);
    }
}
