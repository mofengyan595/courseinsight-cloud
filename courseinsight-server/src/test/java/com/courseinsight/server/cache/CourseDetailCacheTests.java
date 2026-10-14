package com.courseinsight.server.cache;

import com.courseinsight.server.dto.CourseDetailResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CourseDetailCacheTests {

    @Test
    void redisReadFailureShouldFallBackToDatabase() {
        given(redisTemplate.opsForValue()).willThrow(new IllegalStateException("Redis unavailable"));
        assertThat(courseDetailCache.get(1L).hit()).isFalse();
    }

    @Test
    void redisDeleteFailureShouldNotFailCommittedBusinessOperation() {
        given(redisTemplate.delete("course:detail:1")).willThrow(new IllegalStateException("Redis unavailable"));
        org.assertj.core.api.Assertions.assertThatCode(() -> courseDetailCache.evict(1L)).doesNotThrowAnyException();
    }

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CourseDetailCache courseDetailCache;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        courseDetailCache = new CourseDetailCache(redisTemplate, objectMapper);
    }

    @Test
    void shouldReturnMissWhenRedisHasNoValue() {
        stubValueOperations();
        given(valueOperations.get("course:detail:1")).willReturn(null);

        CourseCacheLookup result = courseDetailCache.get(1L);

        assertThat(result.hit()).isFalse();
        assertThat(result.course()).isNull();
    }

    @Test
    void shouldReadCourseFromRedis() throws Exception {
        stubValueOperations();
        CourseDetailResponse course = createResponse();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        given(valueOperations.get("course:detail:1"))
                .willReturn(objectMapper.writeValueAsString(course));

        CourseCacheLookup result = courseDetailCache.get(1L);

        assertThat(result.hit()).isTrue();
        assertThat(result.course()).isEqualTo(course);
    }

    @Test
    void shouldRecognizeCachedNotFoundValue() {
        stubValueOperations();
        given(valueOperations.get("course:detail:999999"))
                .willReturn(CourseDetailCache.NULL_VALUE);

        CourseCacheLookup result = courseDetailCache.get(999999L);

        assertThat(result.hit()).isTrue();
        assertThat(result.course()).isNull();
    }

    @Test
    void shouldWriteCourseWithTtl() {
        stubValueOperations();
        CourseDetailResponse course = createResponse();

        courseDetailCache.put(1L, course);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("course:detail:1"),
                valueCaptor.capture(),
                ttlCaptor.capture()
        );
        assertThat(valueCaptor.getValue()).contains("CS101", "Java程序设计");
        assertThat(ttlCaptor.getValue()).isBetween(Duration.ofMinutes(24), Duration.ofMinutes(30));
    }

    @Test
    void shouldWriteNotFoundValueWithShortTtl() {
        stubValueOperations();
        courseDetailCache.putNotFound(999999L);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("course:detail:999999"),
                org.mockito.ArgumentMatchers.eq(CourseDetailCache.NULL_VALUE), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
    }

    @Test
    void shouldEvictCourseCache() {
        courseDetailCache.evict(1L);

        verify(redisTemplate).delete("course:detail:1");
    }

    private void stubValueOperations() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    private CourseDetailResponse createResponse() {
        return new CourseDetailResponse(
                1L,
                "CS101",
                "Java程序设计",
                "张老师",
                "Java基础课程",
                1,
                LocalDateTime.of(2026, 7, 31, 10, 0),
                LocalDateTime.of(2026, 7, 31, 10, 0)
        );
    }
}
