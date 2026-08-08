package com.courseinsight.server;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
class AnalysisTaskMigrationV14Tests {

    private static final String OLD_SENT_EVENT =
            "11111111111111111111111111111111";
    private static final String TERMINAL_EVENT =
            "22222222222222222222222222222222";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4")
    )
            .withDatabaseName("courseinsight_migration_test")
            .withUsername("courseinsight_test")
            .withPassword("courseinsight_test");

    @Test
    void shouldCreateFreshDurableGenerationsForAmbiguousNonterminalTasks() {
        DataSource dataSource = dataSource();
        migrate(dataSource, "13");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        long waiting = insertTask(jdbc, 101, "WAITING");
        long retryableFailed = insertTask(jdbc, 102, "FAILED");
        jdbc.update(
                "UPDATE analysis_task SET failure_reason = 'temporary' WHERE id = ?",
                retryableFailed
        );

        long ambiguous = insertTask(jdbc, 103, "WAITING");
        jdbc.update(
                "UPDATE analysis_task SET current_event_id = ? WHERE id = ?",
                OLD_SENT_EVENT,
                ambiguous
        );
        insertOutbox(jdbc, ambiguous, 103, OLD_SENT_EVENT, "SENT");

        long success = insertTask(jdbc, 104, "SUCCESS");

        long terminalFailed = insertTask(jdbc, 105, "FAILED");
        jdbc.update(
                """
                UPDATE analysis_task
                SET current_event_id = ?,
                    dead_lettered_at = CURRENT_TIMESTAMP(3),
                    completed_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """,
                TERMINAL_EVENT,
                terminalFailed
        );

        long processing = insertTask(jdbc, 106, "PROCESSING");
        jdbc.update(
                """
                UPDATE analysis_task
                SET current_event_id = ?, execution_token = ?,
                    lease_until = TIMESTAMPADD(
                        MINUTE, 5, CURRENT_TIMESTAMP(3)
                    )
                WHERE id = ?
                """,
                "33333333333333333333333333333333",
                "44444444444444444444444444444444",
                processing
        );

        migrate(dataSource, null);

        assertDurableWaitingGeneration(jdbc, waiting);
        assertDurableWaitingGeneration(jdbc, retryableFailed);
        assertDurableWaitingGeneration(jdbc, ambiguous);
        assertDurableWaitingGeneration(jdbc, processing);

        assertThat(currentEvent(jdbc, ambiguous)).isNotEqualTo(OLD_SENT_EVENT);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM analysis_outbox_event
                WHERE task_id = ? AND event_id = ? AND status = 'SENT'
                """,
                Integer.class,
                ambiguous,
                OLD_SENT_EVENT
        )).isEqualTo(1);

        assertThat(taskStatus(jdbc, success)).isEqualTo("SUCCESS");
        assertThat(outboxCount(jdbc, success)).isZero();

        assertThat(taskStatus(jdbc, terminalFailed)).isEqualTo("FAILED");
        assertThat(currentEvent(jdbc, terminalFailed)).isEqualTo(TERMINAL_EVENT);
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at IS NOT NULL FROM analysis_task WHERE id = ?",
                Boolean.class,
                terminalFailed
        )).isTrue();
        assertThat(outboxCount(jdbc, terminalFailed)).isZero();

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM analysis_outbox_event
                WHERE status = 'PENDING'
                """,
                Integer.class
        )).isEqualTo(4);
    }

    private void assertDurableWaitingGeneration(JdbcTemplate jdbc, long taskId) {
        String eventId = currentEvent(jdbc, taskId);
        assertThat(taskStatus(jdbc, taskId)).isEqualTo("WAITING");
        assertThat(eventId).isNotBlank();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM analysis_outbox_event
                WHERE task_id = ?
                  AND event_id = ?
                  AND status = 'PENDING'
                  AND next_retry_at <= CURRENT_TIMESTAMP(3)
                """,
                Integer.class,
                taskId,
                eventId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM analysis_task
                WHERE id = ?
                  AND execution_token IS NULL
                  AND lease_until IS NULL
                  AND dead_lettered_at IS NULL
                """,
                Integer.class,
                taskId
        )).isEqualTo(1);
    }

    private long insertTask(JdbcTemplate jdbc, int commentId, String status) {
        String taskNo = String.format("%032d", commentId);
        jdbc.update(
                """
                INSERT INTO analysis_task
                    (task_no, comment_id, course_id, status, retry_count)
                VALUES (?, ?, 1, ?, 0)
                """,
                taskNo,
                commentId,
                status
        );
        return jdbc.queryForObject(
                "SELECT id FROM analysis_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
    }

    private void insertOutbox(
            JdbcTemplate jdbc,
            long taskId,
            int commentId,
            String eventId,
            String status) {
        jdbc.update(
                """
                INSERT INTO analysis_outbox_event
                    (event_id, task_id, comment_id, event_type, status,
                     retry_count, next_retry_at, sent_at)
                VALUES (?, ?, ?, 'COMMENT_ANALYSIS_CREATED', ?, 0,
                        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """,
                eventId,
                taskId,
                commentId,
                status
        );
    }

    private String currentEvent(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject(
                "SELECT current_event_id FROM analysis_task WHERE id = ?",
                String.class,
                taskId
        );
    }

    private String taskStatus(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM analysis_task WHERE id = ?",
                String.class,
                taskId
        );
    }

    private int outboxCount(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis_outbox_event WHERE task_id = ?",
                Integer.class,
                taskId
        );
    }

    private void migrate(DataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
    }
}
