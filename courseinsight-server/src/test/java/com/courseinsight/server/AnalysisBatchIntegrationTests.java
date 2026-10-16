package com.courseinsight.server;

import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchProgressAggregate;
import com.courseinsight.server.dto.AnalysisBatchPageQuery;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.AnalysisBatchProgressMapper;
import com.courseinsight.server.service.AnalysisBatchCreationService;
import com.courseinsight.server.service.AnalysisBatchService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
@Transactional
class AnalysisBatchIntegrationTests {

    @Autowired
    private AnalysisBatchCreationService creationService;

    @Autowired
    private AnalysisBatchService batchService;

    @Autowired
    private AnalysisBatchProgressMapper progressMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldListPersistedHistoryWithOwnershipAndStablePagination() {
        Long ownCourse = insertCourse();
        Long otherCourse = insertCourse();
        jdbcTemplate.update("UPDATE course SET owner_user_id = 12 WHERE id = ?", otherCourse);
        var rows = List.of(new AnalysisBatchCommentRow(2, "讲解清晰", 5));
        var first = creationService.create(ownCourse, 11L, "first.csv", rows);
        var second = creationService.create(ownCourse, 11L, "second.csv", rows);
        var other = creationService.create(otherCourse, 12L, "other.csv", rows);
        jdbcTemplate.update("UPDATE analysis_batch SET created_at = '2026-10-16 10:00:00' WHERE id IN (?, ?, ?)",
                first.batchId(), second.batchId(), other.batchId());

        var page = batchService.page(new AnalysisBatchPageQuery(1, 1), 11L, UserRole.TEACHER);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(item -> item.batchId()).containsExactly(second.batchId());
        assertThat(page.items().get(0).courseName()).isEqualTo("Batch Test");
        assertThat(page.items().get(0).originalFilename()).isEqualTo("second.csv");
        assertThat(batchService.page(new AnalysisBatchPageQuery(2, 1), 11L, UserRole.TEACHER).items())
                .extracting(item -> item.batchId()).containsExactly(first.batchId());
        assertThat(batchService.page(new AnalysisBatchPageQuery(3, 1), 11L, UserRole.TEACHER).items()).isEmpty();
        assertThat(batchService.page(new AnalysisBatchPageQuery(1, 10), 12L, UserRole.TEACHER).items())
                .extracting(item -> item.batchId()).containsExactly(other.batchId());
        assertThat(batchService.page(new AnalysisBatchPageQuery(1, 10), 1L, UserRole.ADMIN).items())
                .extracting(item -> item.batchId())
                .containsExactly(other.batchId(), second.batchId(), first.batchId());
    }

    @Test
    void shouldPersistWholeBatchAndAggregateWaitingProgress() {
        Long courseId = insertCourse();

        AnalysisBatchCreateResponse created = creationService.create(
                courseId,
                11L,
                "comments.csv",
                List.of(
                        new AnalysisBatchCommentRow(2, "讲解清晰", 5),
                        new AnalysisBatchCommentRow(3, "进度太快", 2)
                )
        );

        assertThat(count("analysis_batch", "id", created.batchId())).isEqualTo(1);
        assertThat(count("course_comment", "course_id", courseId)).isEqualTo(2);
        assertThat(count("analysis_task", "batch_id", created.batchId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM analysis_outbox_event event
                INNER JOIN analysis_task task ON task.id = event.task_id
                WHERE task.batch_id = ?
                """,
                Integer.class,
                created.batchId()
        )).isEqualTo(2);

        AnalysisBatchProgressAggregate progress =
                progressMapper.selectProgress(created.batchId());
        assertThat(progress.getTotalCount()).isEqualTo(2);
        assertThat(progress.getWaitingCount()).isEqualTo(2L);
        assertThat(progress.getSuccessCount()).isZero();
        assertThat(progress.getFailedCount()).isZero();
    }

    private Long insertCourse() {
        String courseCode = "BT" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, owner_user_id, status)
                VALUES (?, 'Batch Test', 'Test Teacher', 11, 1)
                """,
                courseCode
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );
    }

    private Integer count(String table, String column, Long value) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value
        );
    }
}
