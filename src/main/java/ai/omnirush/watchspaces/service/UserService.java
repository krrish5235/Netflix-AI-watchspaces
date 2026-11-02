package ai.omnirush.watchspaces.service;

import ai.omnirush.watchspaces.domain.Role;
import ai.omnirush.watchspaces.domain.UserAccount;
import ai.omnirush.watchspaces.persistence.UserAccountStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {
    private final PasswordEncoder passwordEncoder;
    private final UserAccountStore accountStore;

    public UserService(PasswordEncoder passwordEncoder, UserAccountStore accountStore) {
        this.passwordEncoder = passwordEncoder;
        this.accountStore = accountStore;
    }

    public UserAccount register(String email, String displayName, String password) {
        String normalizedEmail = normalizeEmail(email);
        UUID id = UUID.randomUUID();
        UserAccount account = new UserAccount(
                id,
                normalizedEmail,
                displayName.trim(),
                passwordEncoder.encode(password),
                Role.VIEWER,
                Instant.now());

        return accountStore.save(account);
    }

    public UserAccount requireByEmail(String email) {
        UserAccount account = accountStore.findByEmail(normalizeEmail(email)).orElse(null);
        if (account == null) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return account;
    }

    public UserAccount requireById(UUID id) {
        UserAccount account = accountStore.findById(id).orElse(null);
        if (account == null) {
            throw new UnauthorizedException("User account no longer exists");
        }
        return account;
    }

    public boolean matchesPassword(UserAccount account, String password) {
        return passwordEncoder.matches(password, account.passwordHash());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
