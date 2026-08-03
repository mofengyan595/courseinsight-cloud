package com.courseinsight.server.cache;

import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CourseAnalyticsCacheTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CourseAnalyticsCache courseAnalyticsCache;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        courseAnalyticsCache = new CourseAnalyticsCache(redisTemplate, objectMapper);
    }

    @Test
    void shouldReturnMissWhenRedisHasNoValue() {
        stubValueOperations();
        given(valueOperations.get("course:analytics:summary:14")).willReturn(null);

        CourseAnalyticsCacheLookup result = courseAnalyticsCache.get(14L);

        assertThat(result.hit()).isFalse();
        assertThat(result.summary()).isNull();
    }

    @Test
    void shouldReadSummaryFromRedis() throws Exception {
        stubValueOperations();
        CourseAnalyticsSummaryResponse summary = createSummary();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        given(valueOperations.get("course:analytics:summary:14"))
                .willReturn(objectMapper.writeValueAsString(summary));

        CourseAnalyticsCacheLookup result = courseAnalyticsCache.get(14L);

        assertThat(result.hit()).isTrue();
        assertThat(result.summary()).isEqualTo(summary);
    }

    @Test
    void shouldWriteSummaryWithTtl() {
        stubValueOperations();

        courseAnalyticsCache.put(14L, createSummary());

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("course:analytics:summary:14"),
                valueCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(CourseAnalyticsCache.SUMMARY_TTL)
        );
        assertThat(valueCaptor.getValue()).contains("totalComments", "completionPercentage");
    }

    @Test
    void shouldEvictSummaryImmediatelyWithoutTransaction() {
        courseAnalyticsCache.evictAfterCommit(14L);

        verify(redisTemplate).delete("course:analytics:summary:14");
    }

    private void stubValueOperations() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    private CourseAnalyticsSummaryResponse createSummary() {
        return new CourseAnalyticsSummaryResponse(
                14L,
                6,
                new BigDecimal("3.50"),
                new CourseAnalyticsSummaryResponse.TaskSummary(
                        6, 1, 1, 3, 1, new BigDecimal("50.00")
                ),
                new CourseAnalyticsSummaryResponse.SentimentSummary(
                        3,
                        1,
                        1,
                        1,
                        new BigDecimal("33.33"),
                        new BigDecimal("33.33"),
                        new BigDecimal("33.33")
                ),
                new CourseAnalyticsSummaryResponse.RiskSummary(1, 1, 0, 1)
        );
    }
}
