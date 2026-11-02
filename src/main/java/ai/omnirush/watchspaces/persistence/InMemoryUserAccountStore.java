package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.domain.UserAccount;
import ai.omnirush.watchspaces.service.ConflictException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Profile("test")
public final class InMemoryUserAccountStore implements UserAccountStore {
    private final ConcurrentMap<UUID, UserAccount> accounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> idsByEmail = new ConcurrentHashMap<>();

    @Override
    public UserAccount save(UserAccount account) {
        UUID existing = idsByEmail.putIfAbsent(account.email(), account.id());
        if (existing != null && !existing.equals(account.id())) {
            throw new ConflictException("An account with this email already exists");
        }
        accounts.put(account.id(), account);
        return account;
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        UUID id = idsByEmail.get(email);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return Optional.ofNullable(accounts.get(id));
    }
}
