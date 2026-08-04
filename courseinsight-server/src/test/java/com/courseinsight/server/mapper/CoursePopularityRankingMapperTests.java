package com.courseinsight.server.mapper;

import com.courseinsight.server.dto.CoursePopularityAggregate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CoursePopularityRankingMapperTests {

    @Autowired
    private CoursePopularityRankingMapper rankingMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCountOnlyActiveComments() {
        String courseCode = "RANK-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, status)
                VALUES (?, 'Ranking Test', 'Test Teacher', 1)
                """,
                courseCode
        );
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );

        jdbcTemplate.update(
                """
                INSERT INTO course_comment (course_id, content, rating, status)
                VALUES (?, 'active comment 1', 5, 1),
                       (?, 'active comment 2', 4, 1),
                       (?, 'deleted comment', 1, 0)
                """,
                courseId,
                courseId,
                courseId
        );

        CoursePopularityAggregate result = rankingMapper
                .selectTopByCommentCount(10_000)
                .stream()
                .filter(item -> courseId.equals(item.getCourseId()))
                .findFirst()
                .orElseThrow();

        assertThat(result.getCommentCount()).isEqualTo(2L);
    }
}
