package app.gitwatch.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RepoPollStateLockTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void skipLockedReturnsOnlyDueReposWithAnActiveSubscription() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        UUID userId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID dueRepo = UUID.randomUUID();
        UUID futureRepo = UUID.randomUUID();
        UUID inactiveRepo = UUID.randomUUID();
        UUID bareRepo = UUID.randomUUID();

        try (Connection connection = open()) {
            insertUser(connection, userId);
            insertChannel(connection, channelId, userId);
            insertRepo(connection, dueRepo, "apache", "iggy");
            insertRepo(connection, futureRepo, "apache", "later");
            insertRepo(connection, inactiveRepo, "apache", "paused");
            insertRepo(connection, bareRepo, "apache", "nosub");
            insertSubscription(connection, userId, dueRepo, channelId, true);
            insertSubscription(connection, userId, futureRepo, channelId, true);
            insertSubscription(connection, userId, inactiveRepo, channelId, false);
            insertPoll(connection, "apache", "iggy", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            insertPoll(connection, "apache", "later", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
            insertPoll(connection, "apache", "paused", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            insertPoll(connection, "apache", "nosub", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        }

        try (Connection first = open(); Connection second = open()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try (var statement = second.createStatement()) {
                statement.execute("SET statement_timeout = '2s'");
            }
            try (PreparedStatement query = first.prepareStatement(PollLockSql.statement(10, "?"))) {
                query.setObject(1, OffsetDateTime.now(ZoneOffset.UTC));
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("apache", rows.getString(1));
                    assertEquals("iggy", rows.getString(2));
                    assertFalse(rows.next());
                }
            }
            try (PreparedStatement query = second.prepareStatement(PollLockSql.statement(10, "?"))) {
                query.setObject(1, OffsetDateTime.now(ZoneOffset.UTC));
                try (ResultSet rows = query.executeQuery()) {
                    assertFalse(rows.next());
                }
            }
            first.commit();
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void insertUser(Connection connection, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (id, email) VALUES (?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, id + "@example.invalid");
            statement.executeUpdate();
        }
    }

    private void insertChannel(Connection connection, UUID id, UUID userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO channels (id, user_id, type, name, config, is_active) VALUES (?, ?, 'discord', 'dev', '{}'::jsonb, TRUE)")) {
            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.executeUpdate();
        }
    }

    private void insertRepo(Connection connection, UUID id, String owner, String repo) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO repos (id, owner, repo, full_name, is_active) VALUES (?, ?, ?, ?, TRUE)")) {
            statement.setObject(1, id);
            statement.setString(2, owner);
            statement.setString(3, repo);
            statement.setString(4, owner + "/" + repo);
            statement.executeUpdate();
        }
    }

    private void insertSubscription(Connection connection, UUID userId, UUID repoId, UUID channelId, boolean active) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO subscriptions (id, user_id, repo_id, channel_id, event_type, filters, is_active) VALUES (?, ?, ?, ?, 'issue.opened', '{}'::jsonb, ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, userId);
            statement.setObject(3, repoId);
            statement.setObject(4, channelId);
            statement.setBoolean(5, active);
            statement.executeUpdate();
        }
    }

    private void insertPoll(Connection connection, String owner, String repo, OffsetDateTime nextPollAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO repo_poll_state (owner, repo, next_poll_at) VALUES (?, ?, ?)")) {
            statement.setString(1, owner);
            statement.setString(2, repo);
            statement.setObject(3, nextPollAt);
            statement.executeUpdate();
        }
    }
}
