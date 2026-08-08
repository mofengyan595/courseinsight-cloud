package com.courseinsight.server;

import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MySqlIntegrationTest
class AnalysisOutboxSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void analysisOutboxEventTableExists() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_outbox_event'
                """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }

    @Test
    void outboxAllowsMultipleReplayEventsForOneTask() {
        Integer obsoleteUniqueIndexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_outbox_event'
                  AND index_name = 'uk_analysis_outbox_task'
                """,
                Integer.class
        );
        Integer replayLookupIndexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_outbox_event'
                  AND index_name = 'idx_analysis_outbox_task_created'
                """,
                Integer.class
        );

        assertEquals(0, obsoleteUniqueIndexCount);
        assertEquals(1, replayLookupIndexCount);
    }
}
