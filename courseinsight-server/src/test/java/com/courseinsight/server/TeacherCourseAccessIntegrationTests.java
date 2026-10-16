package com.courseinsight.server;

import com.courseinsight.server.cache.CourseCacheLookup;
import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.dto.*;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.mapper.AppUserMapper;
import com.courseinsight.server.mapper.CourseMapper;
import com.courseinsight.server.service.*;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@MySqlIntegrationTest
@Transactional
class TeacherCourseAccessIntegrationTests {
    @Autowired private CourseService courses;
    @Autowired private CommentService comments;
    @Autowired private CommentAnalysisService analysis;
    @Autowired private CourseAnalyticsService analytics;
    @Autowired private AnalysisBatchCreationService batchCreation;
    @Autowired private AnalysisBatchService batches;
    @Autowired private AnalysisBatchResultService results;
    @Autowired private AppUserMapper users;
    @Autowired private CourseMapper courseMapper;
    @Autowired private CourseDetailCache cache;
    @Autowired private AdminUserService accounts;

    @Test
    void assignmentAndTransferShouldControlEveryTeacherReadEvenWithCachedCourseDetails() {
        Long firstTeacher = user(UserRole.TEACHER, 1);
        Long secondTeacher = user(UserRole.TEACHER, 1);
        Long student = user(UserRole.STUDENT, 1);
        assertThat(accounts.page(new AdminUserPageQuery(1, 10, null, "TEACHER", 1, firstTeacher)).items())
                .extracting(AdminUserResponse::id).containsExactly(firstTeacher);
        String code = "ASSIGN-" + UUID.randomUUID().toString().substring(0, 12);
        Long courseId = courses.create(1L, UserRole.ADMIN,
                new CourseCreateRequest(code, "课程归属测试", "教师甲", "", firstTeacher));
        given(cache.get(courseId)).willReturn(CourseCacheLookup.found(
                CourseDetailResponse.from(courseMapper.selectById(courseId))));
        Long commentId = comments.create(courseId, student, UserRole.STUDENT,
                new CommentCreateRequest("学生反馈", 5));
        var batch = batchCreation.create(courseId, 1L, "assigned.csv",
                List.of(new AnalysisBatchCommentRow(2, "导入评价", 4)));

        assertThat(courses.pageForUser(new CoursePageQuery(1, 10, code), firstTeacher, UserRole.TEACHER).total()).isEqualTo(1);
        assertThat(courses.pageForUser(new CoursePageQuery(1, 10, code), secondTeacher, UserRole.TEACHER).items()).isEmpty();
        assertThat(courses.getForUser(courseId, firstTeacher, UserRole.TEACHER).id()).isEqualTo(courseId);
        assertThat(comments.pageForUser(courseId, new CommentPageQuery(1, 10), firstTeacher, UserRole.TEACHER).total()).isEqualTo(2);
        assertThat(analysis.getByCommentId(commentId, firstTeacher, UserRole.TEACHER).task().status()).isEqualTo("WAITING");
        assertThat(batches.page(new AnalysisBatchPageQuery(1, 10), firstTeacher, UserRole.TEACHER).items())
                .extracting(AnalysisBatchSummaryResponse::batchId).containsExactly(batch.batchId());
        assertThat(batches.page(new AnalysisBatchPageQuery(1, 10), secondTeacher, UserRole.TEACHER).items()).isEmpty();
        denyReads(courseId, commentId, batch.batchId(), secondTeacher);

        courses.update(1L, UserRole.ADMIN, courseId,
                new CourseUpdateRequest(code, "课程归属测试", "教师乙", "", 1, secondTeacher));
        assertThat(courseMapper.selectById(courseId).getOwnerUserId()).isEqualTo(secondTeacher);
        denyReads(courseId, commentId, batch.batchId(), firstTeacher);
        assertThat(batches.page(new AnalysisBatchPageQuery(1, 10), firstTeacher, UserRole.TEACHER).items()).isEmpty();
        assertThat(courses.getForUser(courseId, secondTeacher, UserRole.TEACHER).id()).isEqualTo(courseId);
        assertThat(analysis.getByCommentId(commentId, secondTeacher, UserRole.TEACHER).task().status()).isEqualTo("WAITING");
        assertThat(batches.getProgress(batch.batchId(), secondTeacher, UserRole.TEACHER).batchId()).isEqualTo(batch.batchId());
        assertThat(batches.page(new AnalysisBatchPageQuery(1, 10), secondTeacher, UserRole.TEACHER).items())
                .extracting(AnalysisBatchSummaryResponse::batchId).containsExactly(batch.batchId());
        assertThat(courses.pageForUser(new CoursePageQuery(1, 10, code), student, UserRole.STUDENT).total()).isEqualTo(1);
    }

    @Test
    void shouldRejectMissingStudentAdminAndDisabledTeacherAssignments() {
        for (Long target : List.of(user(UserRole.STUDENT, 1), user(UserRole.ADMIN, 1), user(UserRole.TEACHER, 0), Long.MAX_VALUE)) {
            assertThatThrownBy(() -> courses.create(1L, UserRole.ADMIN,
                    new CourseCreateRequest("BAD", "无效分配", "教师", "", target)))
                    .isInstanceOf(CourseAccessDeniedException.class);
        }
    }

    private void denyReads(Long courseId, Long commentId, Long batchId, Long teacherId) {
        assertThatThrownBy(() -> courses.getForUser(courseId, teacherId, UserRole.TEACHER)).isInstanceOf(CourseAccessDeniedException.class);
        assertThatThrownBy(() -> comments.pageForUser(courseId, new CommentPageQuery(1, 10), teacherId, UserRole.TEACHER)).isInstanceOf(CourseAccessDeniedException.class);
        assertThatThrownBy(() -> analysis.getByCommentId(commentId, teacherId, UserRole.TEACHER)).isInstanceOf(CourseAccessDeniedException.class);
        assertThatThrownBy(() -> analytics.getSummary(courseId, teacherId, UserRole.TEACHER)).isInstanceOf(CourseAccessDeniedException.class);
        assertThatThrownBy(() -> batches.getProgress(batchId, teacherId, UserRole.TEACHER)).isInstanceOf(CourseAccessDeniedException.class);
        assertThatThrownBy(() -> results.page(batchId, teacherId, UserRole.TEACHER, new AnalysisBatchResultPageQuery(1, 10))).isInstanceOf(CourseAccessDeniedException.class);
        assertThatThrownBy(() -> results.authorizeCsvExport(batchId, teacherId, UserRole.TEACHER)).isInstanceOf(CourseAccessDeniedException.class);
    }

    private Long user(UserRole role, int status) {
        AppUser user = new AppUser();
        user.setUsername("assign_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        user.setPasswordHash("test-only-hash");
        user.setDisplayName("测试教师");
        user.setRole(role.name());
        user.setStatus(status);
        users.insert(user);
        return user.getId();
    }
}
