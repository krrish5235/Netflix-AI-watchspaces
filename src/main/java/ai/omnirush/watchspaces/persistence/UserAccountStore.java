package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.domain.UserAccount;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for account data. Application services depend on this
 * contract so the test profile can remain deterministic without weakening the
 * production PostgreSQL path.
 */
public interface UserAccountStore {
    UserAccount save(UserAccount account);

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findById(UUID id);
}
