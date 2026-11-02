package ai.omnirush.watchspaces.persistence;

import ai.omnirush.watchspaces.domain.AiVerbosity;
import ai.omnirush.watchspaces.domain.WatchSpace;
import ai.omnirush.watchspaces.domain.WatchSpaceStatus;
import ai.omnirush.watchspaces.service.ConflictException;
import ai.omnirush.watchspaces.service.NotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
@Profile("!test")
public class PostgresWatchSpaceStore implements WatchSpaceStore {
    private final JdbcTemplate jdbcTemplate;

    public PostgresWatchSpaceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public WatchSpace save(WatchSpace watchSpace) {
        WatchSpace snapshot = watchSpace.copy();
        persist(snapshot);
        return snapshot;
    }

    @Override
    @Transactional
    public WatchSpace update(UUID id, Consumer<WatchSpace> mutation) {
        WatchSpace watchSpace = queryOne("SELECT * FROM watch_spaces WHERE id = ? FOR UPDATE", id)
                .orElseThrow(() -> new NotFoundException("Watch Space was not found"));
        // Authorization and validation in the callback see the state protected by the row lock.
        mutation.accept(watchSpace);
        WatchSpace snapshot = watchSpace.copy();
        persist(snapshot);
        return snapshot;
    }

    private void persist(WatchSpace watchSpace) {
        try {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE watch_spaces SET
                        title_id = ?, host_user_id = ?, max_participants = ?,
                        ai_verbosity = ?, voting_enabled = ?, status = ?, status_before_lock = ?
                    WHERE id = ?
                    """,
                    watchSpace.titleId(),
                    watchSpace.hostUserId(),
                    watchSpace.maxParticipants(),
                    watchSpace.aiVerbosity().name(),
                    watchSpace.votingEnabled(),
                    watchSpace.status().name(),
                    watchSpace.statusBeforeLock().name(),
                    watchSpace.id());
            if (updated == 0) {
                jdbcTemplate.update(
                        """
                        INSERT INTO watch_spaces
                            (id, title_id, host_user_id, invite_code, max_participants,
                             ai_verbosity, voting_enabled, status, status_before_lock, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        watchSpace.id(),
                        watchSpace.titleId(),
                        watchSpace.hostUserId(),
                        watchSpace.inviteCode(),
                        watchSpace.maxParticipants(),
                        watchSpace.aiVerbosity().name(),
                        watchSpace.votingEnabled(),
                        watchSpace.status().name(),
                        watchSpace.statusBeforeLock().name(),
                        Timestamp.from(watchSpace.createdAt()));
            }

            Set<UUID> existingParticipants = participantIds(watchSpace.id());
            Set<UUID> participants = watchSpace.participantIds();
            jdbcTemplate.batchUpdate(
                    "DELETE FROM watch_space_participants WHERE watch_space_id = ? AND user_id = ?",
                    existingParticipants.stream()
                            .filter(participantId -> !participants.contains(participantId))
                            .map(participantId -> new Object[] {watchSpace.id(), participantId})
                            .toList());
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO watch_space_participants (watch_space_id, user_id, joined_at)
                    VALUES (?, ?, ?)
                    """,
                    participants.stream()
                            .filter(participantId -> !existingParticipants.contains(participantId))
                            .map(participantId -> new Object[] {
                                    watchSpace.id(), participantId, Timestamp.from(Instant.now())
                            }).toList());
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Watch Space references an invalid account or invite code");
        }
    }

    @Override
    // Room fields and membership are separate queries and must share one snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<WatchSpace> findById(UUID id) {
        return queryOne("SELECT * FROM watch_spaces WHERE id = ?", id);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<WatchSpace> findByInviteCode(String inviteCode) {
        return queryOne("SELECT * FROM watch_spaces WHERE invite_code = ?", inviteCode);
    }

    @Override
    public boolean inviteCodeExists(String inviteCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watch_spaces WHERE invite_code = ?", Integer.class, inviteCode);
        return count != null && count > 0;
    }

    private Optional<WatchSpace> queryOne(String sql, Object parameter) {
        return jdbcTemplate.query(sql, this::map, parameter).stream().findFirst();
    }

    private Set<UUID> participantIds(UUID id) {
        return new LinkedHashSet<>(jdbcTemplate.query(
                "SELECT user_id FROM watch_space_participants WHERE watch_space_id = ?",
                (participantResult, ignored) -> participantResult.getObject("user_id", UUID.class),
                id));
    }

    private WatchSpace map(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        return new WatchSpace(
                id,
                resultSet.getString("title_id"),
                resultSet.getObject("host_user_id", UUID.class),
                resultSet.getString("invite_code"),
                resultSet.getInt("max_participants"),
                AiVerbosity.valueOf(resultSet.getString("ai_verbosity")),
                resultSet.getBoolean("voting_enabled"),
                resultSet.getTimestamp("created_at").toInstant(),
                WatchSpaceStatus.valueOf(resultSet.getString("status")),
                WatchSpaceStatus.valueOf(resultSet.getString("status_before_lock")),
                participantIds(id));
    }
}
