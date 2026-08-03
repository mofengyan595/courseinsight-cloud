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

    @Test
    void courseCommentTracksAnonymousUserOwnership() {
        Integer ownershipColumnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'course_comment'
                  AND column_name IN ('user_id', 'is_anonymous')
                """,
                Integer.class
        );
        Integer activeUniqueIndexColumnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'course_comment'
                  AND index_name = 'uk_comment_course_active_user'
                  AND non_unique = 0
                """,
                Integer.class
        );
        Integer generatedColumnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'course_comment'
                  AND column_name = 'active_user_id'
                  AND extra LIKE '%STORED GENERATED%'
                """,
                Integer.class
        );
        Integer retiredIndexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'course_comment'
                  AND index_name = 'uk_comment_course_user'
                """,
                Integer.class
        );

        assertEquals(2, ownershipColumnCount);
        assertEquals(2, activeUniqueIndexColumnCount);
        assertEquals(1, generatedColumnCount);
        assertEquals(0, retiredIndexCount);
    }
}
