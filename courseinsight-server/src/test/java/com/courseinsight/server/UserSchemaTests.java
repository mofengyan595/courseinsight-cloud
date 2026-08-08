package com.courseinsight.server;

import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
class UserSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appUserTableAndUsernameUniqueIndexExist() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'app_user'
                """,
                Integer.class
        );
        Integer uniqueIndexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'app_user'
                  AND index_name = 'uk_app_user_username'
                  AND non_unique = 0
                """,
                Integer.class
        );

        assertThat(tableCount).isEqualTo(1);
        assertThat(uniqueIndexCount).isEqualTo(1);
    }
}
