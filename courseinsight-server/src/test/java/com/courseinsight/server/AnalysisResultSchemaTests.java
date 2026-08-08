package com.courseinsight.server;

import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MySqlIntegrationTest
class AnalysisResultSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void analysisResultTableExists() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'analysis_result'
                """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }
}
