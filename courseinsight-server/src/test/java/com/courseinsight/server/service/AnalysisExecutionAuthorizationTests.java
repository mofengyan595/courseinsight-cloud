package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.client.AiAnalysisClient;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisExecutionAuthorizationTests {

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @Mock
    private CourseCommentMapper courseCommentMapper;

    @Mock
    private AiAnalysisClient aiAnalysisClient;

    @Mock
    private AnalysisResultPersistenceService persistenceService;

    @Mock
    private CourseManagementAccessService managementAccessService;

    @Mock
    private CourseAnalyticsCache courseAnalyticsCache;

    @Mock
    private RedisRateLimiter rateLimiter;

    @InjectMocks
    private AnalysisExecutionService analysisExecutionService;

    @Test
    void shouldRejectExecutionForAnotherTeachersCourseBeforeCallingAi() {
        AnalysisTask task = new AnalysisTask();
        task.setId(6L);
        task.setCommentId(13L);
        task.setCourseId(14L);
        task.setStatus("WAITING");
        given(analysisTaskMapper.selectById(6L)).willReturn(task);
        willThrow(new CourseAccessDeniedException("无权管理其他教师的课程"))
                .given(managementAccessService)
                .assertCanManage(14L, 12L, UserRole.TEACHER);

        assertThatThrownBy(() -> analysisExecutionService.executeForUser(
                6L,
                12L,
                UserRole.TEACHER
        )).isInstanceOf(CourseAccessDeniedException.class)
                .hasMessage("无权管理其他教师的课程");

        org.mockito.Mockito.verify(rateLimiter)
                .check(RateLimitPolicy.MANUAL_ANALYSIS, 12L);
        verifyNoInteractions(courseCommentMapper, aiAnalysisClient, persistenceService);
    }
}
