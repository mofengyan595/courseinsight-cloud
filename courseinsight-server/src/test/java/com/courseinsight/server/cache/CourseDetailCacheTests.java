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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CourseDetailCacheTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CourseDetailCache courseDetailCache;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        courseDetailCache = new CourseDetailCache(redisTemplate, objectMapper);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    @Test
    void shouldReturnMissWhenRedisHasNoValue() {
        given(valueOperations.get("course:detail:1")).willReturn(null);

        CourseCacheLookup result = courseDetailCache.get(1L);

        assertThat(result.hit()).isFalse();
        assertThat(result.course()).isNull();
    }

    @Test
    void shouldReadCourseFromRedis() throws Exception {
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
        given(valueOperations.get("course:detail:999999"))
                .willReturn(CourseDetailCache.NULL_VALUE);

        CourseCacheLookup result = courseDetailCache.get(999999L);

        assertThat(result.hit()).isTrue();
        assertThat(result.course()).isNull();
    }

    @Test
    void shouldWriteCourseWithTtl() {
        CourseDetailResponse course = createResponse();

        courseDetailCache.put(1L, course);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("course:detail:1"),
                valueCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(CourseDetailCache.COURSE_TTL)
        );
        assertThat(valueCaptor.getValue()).contains("CS101", "Java程序设计");
    }

    @Test
    void shouldWriteNotFoundValueWithShortTtl() {
        courseDetailCache.putNotFound(999999L);

        verify(valueOperations).set(
                "course:detail:999999",
                CourseDetailCache.NULL_VALUE,
                CourseDetailCache.NULL_TTL
        );
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
