package ai.omnirush.watchspaces.service;

import ai.omnirush.watchspaces.api.auth.AuthResponse;
import ai.omnirush.watchspaces.api.auth.LoginRequest;
import ai.omnirush.watchspaces.api.auth.RefreshRequest;
import ai.omnirush.watchspaces.api.auth.RegisterRequest;
import ai.omnirush.watchspaces.domain.UserAccount;
import ai.omnirush.watchspaces.security.JwtService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {
    private final UserService userService;
    private final JwtService jwtService;

    public AuthService(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        UserAccount account = userService.register(request.email(), request.displayName(), request.password());
        return issueTokens(account);
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount account = userService.requireByEmail(request.email());
        if (!userService.matchesPassword(account, request.password())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return issueTokens(account);
    }

    public AuthResponse refresh(RefreshRequest request) {
        JwtService.TokenClaims claims;
        try {
            claims = jwtService.parseRefreshToken(request.refreshToken());
        } catch (RuntimeException exception) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        UserAccount account = userService.requireById(claims.userId());
        return issueTokens(account);
    }

    private AuthResponse issueTokens(UserAccount account) {
        return new AuthResponse(
                jwtService.createAccessToken(account.id(), account.role()),
                jwtService.createRefreshToken(account.id(), account.role()),
                new AuthResponse.UserSummary(
                        account.id(),
                        account.email(),
                        account.displayName(),
                        account.role(),
                        account.createdAt()));
    }
}
