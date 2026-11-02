package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.domain.Role;
import ai.omnirush.watchspaces.domain.UserAccount;
import ai.omnirush.watchspaces.service.ConflictException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class PostgresUserAccountStore implements UserAccountStore {
    private final JdbcTemplate jdbcTemplate;

    public PostgresUserAccountStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserAccount save(UserAccount account) {
        try {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE user_accounts
                    SET email = ?, display_name = ?, password_hash = ?, role = ?
                    WHERE id = ?
                    """,
                    account.email(),
                    account.displayName(),
                    account.passwordHash(),
                    account.role().name(),
                    account.id());
            if (updated == 0) {
                jdbcTemplate.update(
                        """
                        INSERT INTO user_accounts (id, email, display_name, password_hash, role, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        account.id(),
                        account.email(),
                        account.displayName(),
                        account.passwordHash(),
                        account.role().name(),
                        Timestamp.from(account.createdAt()));
            }
            return account;
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("An account with this email already exists");
        }
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        return queryOne("SELECT * FROM user_accounts WHERE email = ?", email);
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return queryOne("SELECT * FROM user_accounts WHERE id = ?", id);
    }

    private Optional<UserAccount> queryOne(String sql, Object parameter) {
        return jdbcTemplate.query(sql, this::map, parameter).stream().findFirst();
    }

    private UserAccount map(ResultSet resultSet, int rowNumber) throws SQLException {
        Instant createdAt = resultSet.getTimestamp("created_at").toInstant();
        return new UserAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("password_hash"),
                Role.valueOf(resultSet.getString("role")),
                createdAt);
    }
}
