package com.courseinsight.server;

import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchProgressAggregate;
import com.courseinsight.server.mapper.AnalysisBatchProgressMapper;
import com.courseinsight.server.service.AnalysisBatchCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AnalysisBatchIntegrationTests {

    @Autowired
    private AnalysisBatchCreationService creationService;

    @Autowired
    private AnalysisBatchProgressMapper progressMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
