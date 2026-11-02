package ai.omnirush.watchspaces.security;

import ai.omnirush.watchspaces.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey signingKey;
    private final Duration accessTokenLifetime;
    private final Duration refreshTokenLifetime;

    public JwtService(
            @Value("${security.jwt.secret}") String rawSecret,
            @Value("${security.jwt.access-token-minutes:15}") long accessTokenMinutes,
            @Value("${security.jwt.refresh-token-days:7}") long refreshTokenDays) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured before starting the API");
        }
        if (rawSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(rawSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenLifetime = Duration.ofMinutes(accessTokenMinutes);
        this.refreshTokenLifetime = Duration.ofDays(refreshTokenDays);
    }

    public String createAccessToken(UUID userId, Role role) {
        return createToken(userId, role, "access", accessTokenLifetime);
    }

    public String createRefreshToken(UUID userId, Role role) {
        return createToken(userId, role, "refresh", refreshTokenLifetime);
    }

    public TokenClaims parseAccessToken(String token) {
        return parse(token, "access");
    }

    public TokenClaims parseRefreshToken(String token) {
        return parse(token, "refresh");
    }

    private String createToken(UUID userId, Role role, String type, Duration lifetime) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role.name())
                .claim("type", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(signingKey)
                .compact();
    }

    private TokenClaims parse(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String type = claims.get("type", String.class);
        if (!expectedType.equals(type)) {
            throw new IllegalArgumentException("Unexpected JWT token type");
        }

        UUID userId = UUID.fromString(claims.getSubject());
        Role role = Role.valueOf(claims.get("role", String.class));
        return new TokenClaims(userId, role);
    }

    public record TokenClaims(UUID userId, Role role) {
    }
}
