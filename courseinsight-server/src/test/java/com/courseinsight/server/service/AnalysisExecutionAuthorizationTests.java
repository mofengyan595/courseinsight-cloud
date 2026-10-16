package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.client.AiAnalysisClient;
import com.courseinsight.server.config.AnalysisExecutionProperties;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Mock
    private AnalysisExecutionProperties executionProperties;

    @Mock
    private CourseInsightMetrics metrics;

    @InjectMocks
    private AnalysisExecutionService analysisExecutionService;

    @Test
    void shouldRejectTeacherBeforeCallingAiOrWritingTaskState() {
        assertThatThrownBy(() -> analysisExecutionService.executeForUser(6L, 12L, UserRole.TEACHER))
                .isInstanceOf(CourseAccessDeniedException.class)
                .hasMessage("仅管理员可修改课程或分析任务");
        verifyNoInteractions(analysisTaskMapper, courseCommentMapper, aiAnalysisClient,
                persistenceService, managementAccessService, rateLimiter);
    }
}
