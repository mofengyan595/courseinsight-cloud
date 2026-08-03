package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.cache.CourseAnalyticsCacheLookup;
import com.courseinsight.server.dto.CourseAnalyticsAggregate;
import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.mapper.CourseAnalyticsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CourseAnalyticsServiceTests {

    @Mock
    private CourseManagementAccessService managementAccessService;

    @Mock
    private CourseAnalyticsMapper courseAnalyticsMapper;

    @Mock
    private CourseAnalyticsCache courseAnalyticsCache;

    private CourseAnalyticsService courseAnalyticsService;

    @BeforeEach
    void setUp() {
        courseAnalyticsService = new CourseAnalyticsService(
                managementAccessService,
                courseAnalyticsMapper,
                courseAnalyticsCache
        );
    }

    @Test
    void shouldCalculateSummaryAndPercentages() {
        given(courseAnalyticsCache.get(14L))
                .willReturn(CourseAnalyticsCacheLookup.miss());
        given(courseAnalyticsMapper.selectSummary(14L)).willReturn(aggregate());

        CourseAnalyticsSummaryResponse response = courseAnalyticsService.getSummary(
                14L,
                11L,
                UserRole.TEACHER
        );

        verify(managementAccessService).assertCanManage(
                14L,
                11L,
                UserRole.TEACHER
        );
        assertThat(response.courseId()).isEqualTo(14L);
        assertThat(response.totalComments()).isEqualTo(6);
        assertThat(response.averageRating()).isEqualByComparingTo("3.00");
        assertThat(response.tasks().total()).isEqualTo(6);
        assertThat(response.tasks().completionPercentage())
                .isEqualByComparingTo("50.00");
        assertThat(response.sentiments().positivePercentage())
                .isEqualByComparingTo("33.33");
        assertThat(response.sentiments().neutralPercentage())
                .isEqualByComparingTo("33.33");
        assertThat(response.sentiments().negativePercentage())
                .isEqualByComparingTo("33.33");
        assertThat(response.risks().unclassified()).isEqualTo(1);
        verify(courseAnalyticsCache).put(14L, response);
    }

    @Test
    void shouldReturnCachedSummaryWithoutQueryingDatabase() {
        CourseAnalyticsSummaryResponse cached =
                CourseAnalyticsSummaryResponse.from(14L, aggregate());
        given(courseAnalyticsCache.get(14L))
                .willReturn(CourseAnalyticsCacheLookup.found(cached));

        CourseAnalyticsSummaryResponse response = courseAnalyticsService.getSummary(
                14L,
                11L,
                UserRole.TEACHER
        );

        assertThat(response).isSameAs(cached);
        verifyNoInteractions(courseAnalyticsMapper);
    }

    @Test
    void shouldStopBeforeQueryWhenCourseAccessIsDenied() {
        willThrow(new CourseAccessDeniedException("无权管理其他教师的课程"))
                .given(managementAccessService)
                .assertCanManage(14L, 12L, UserRole.TEACHER);

        assertThatThrownBy(() -> courseAnalyticsService.getSummary(
                14L,
                12L,
                UserRole.TEACHER
        )).isInstanceOf(CourseAccessDeniedException.class);

        verifyNoInteractions(courseAnalyticsMapper);
    }

    private CourseAnalyticsAggregate aggregate() {
        CourseAnalyticsAggregate aggregate = new CourseAnalyticsAggregate();
        aggregate.setTotalComments(6L);
        aggregate.setAverageRating(new BigDecimal("3.000000"));
        aggregate.setTotalTasks(6L);
        aggregate.setWaitingTasks(1L);
        aggregate.setProcessingTasks(1L);
        aggregate.setSuccessTasks(3L);
        aggregate.setFailedTasks(1L);
        aggregate.setAnalyzedResults(3L);
        aggregate.setPositiveResults(1L);
        aggregate.setNeutralResults(1L);
        aggregate.setNegativeResults(1L);
        aggregate.setHighRiskResults(1L);
        aggregate.setMiddleRiskResults(1L);
        aggregate.setLowRiskResults(0L);
        return aggregate;
    }
}
