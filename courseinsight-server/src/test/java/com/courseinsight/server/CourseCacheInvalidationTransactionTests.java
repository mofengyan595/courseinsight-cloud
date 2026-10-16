package com.courseinsight.server;

import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.dto.CourseUpdateRequest;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.service.CourseService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@MySqlIntegrationTest
class CourseCacheInvalidationTransactionTests {

    @Autowired
    private CourseService service;

    @Autowired
    private CourseDetailCache cache;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void cacheShouldOnlyBeDeletedAfterCommit() {
        checkInvalidation(false);
    }

    @Test
    void rolledBackUpdateShouldNotDeleteCache() {
        checkInvalidation(true);
    }

    private void checkInvalidation(boolean rollback) {
        String code = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO course (code, name, teacher_name, owner_user_id) VALUES (?, 'before', 'teacher', 11)", code);
        Long id = jdbc.queryForObject("SELECT id FROM course WHERE code = ?", Long.class, code);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.update(11L, UserRole.ADMIN, id,
                        new CourseUpdateRequest(code, "after", "teacher", null, 1));
                verify(cache, never()).evict(id);
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            if (rollback) {
                verify(cache, never()).evict(id);
            } else {
                verify(cache).evict(id);
            }
            assertThat(jdbc.queryForObject("SELECT name FROM course WHERE id = ?", String.class, id))
                    .isEqualTo(rollback ? "before" : "after");
        } finally {
            jdbc.update("DELETE FROM course WHERE id = ?", id);
        }
    }
}
