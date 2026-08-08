package com.courseinsight.server;

import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
class AnalysisBatchSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateBatchTableAndTaskBatchRelation() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_batch'
                """,
                Integer.class
        );
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_task'
                  AND column_name = 'batch_id'
                """,
                Integer.class
        );

        assertThat(tableCount).isEqualTo(1);
        assertThat(columnCount).isEqualTo(1);
    }
}
