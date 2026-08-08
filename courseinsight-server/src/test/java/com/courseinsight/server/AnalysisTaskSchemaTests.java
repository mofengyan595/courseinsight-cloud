package com.courseinsight.server;

import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MySqlIntegrationTest
class AnalysisTaskSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void analysisTaskTableExists() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_task'
                """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }

    @Test
    void analysisTaskHasDeadLetterMarker() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_task'
                  AND column_name = 'dead_lettered_at'
                """,
                Integer.class
        );

        assertEquals(1, columnCount);
    }

    @Test
    void analysisTaskHasExecutionFencingColumnsAndLeaseIndex() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_task'
                  AND column_name IN (
                      'current_event_id', 'execution_token', 'lease_until'
                  )
                """,
                Integer.class
        );
        Integer indexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_task'
                  AND index_name = 'idx_analysis_task_expired_lease'
                """,
                Integer.class
        );

        assertEquals(3, columnCount);
        assertEquals(1, indexCount);
    }
}
