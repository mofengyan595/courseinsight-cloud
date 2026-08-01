package com.courseinsight.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class CommentSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void courseCommentTableExists() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'course_comment'
                """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }
}
