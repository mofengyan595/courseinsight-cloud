package com.courseinsight.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
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
}
