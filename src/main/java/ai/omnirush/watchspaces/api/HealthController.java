package ai.omnirush.watchspaces.api;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", "social-watch-space-api", Instant.now());
    }

    public record HealthResponse(String status, String service, Instant timestamp) {
    }
}
