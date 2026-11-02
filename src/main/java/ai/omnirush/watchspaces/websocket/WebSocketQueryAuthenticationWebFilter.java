package ai.omnirush.watchspaces.websocket;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects legacy query credentials before WebSocket upgrade. Authentication is
 * bearer-header-only; credentials in a URI must never reach upgrade diagnostics.
 * Neither the token value nor the request URI is logged or echoed here.
 */
@Component
@Order(-200)
public final class WebSocketQueryAuthenticationWebFilter implements WebFilter {
    private static final String WEBSOCKET_PATH_PREFIX = "/api/v1/ws/watch-spaces/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var query = exchange.getRequest().getQueryParams();
        if (exchange.getRequest().getPath().value().startsWith(WEBSOCKET_PATH_PREFIX)
                && (query.containsKey("access_token") || query.containsKey("token"))) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
