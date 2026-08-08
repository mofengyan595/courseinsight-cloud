package com.courseinsight.server;

import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MySqlIntegrationTest
class CourseSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void courseTableExists() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'course'
                """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }

    @Test
    void courseTableHasOwnerAndManagementIndex() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'course'
                  AND column_name = 'owner_user_id'
                """,
                Integer.class
        );
        Integer indexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'course'
                  AND index_name = 'idx_course_owner_status'
                """,
                Integer.class
        );

        assertEquals(1, columnCount);
        assertEquals(2, indexCount);
    }
}
