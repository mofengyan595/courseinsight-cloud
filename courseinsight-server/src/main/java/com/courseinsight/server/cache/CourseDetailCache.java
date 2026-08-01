package com.courseinsight.server.cache;

import com.courseinsight.server.dto.CourseDetailResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CourseDetailCache {

    static final String KEY_PREFIX = "course:detail:";
    static final String NULL_VALUE = "__NULL__";
    static final Duration COURSE_TTL = Duration.ofMinutes(30);
    static final Duration NULL_TTL = Duration.ofMinutes(5);

    private static final Logger LOGGER = LoggerFactory.getLogger(CourseDetailCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CourseDetailCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public CourseCacheLookup get(Long courseId) {
        String key = buildKey(courseId);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return CourseCacheLookup.miss();
            }
            if (NULL_VALUE.equals(value)) {
                return CourseCacheLookup.notFound();
            }
            return CourseCacheLookup.found(
                    objectMapper.readValue(value, CourseDetailResponse.class)
            );
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Invalid course cache value for courseId={}", courseId, exception);
            deleteQuietly(key);
            return CourseCacheLookup.miss();
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis read failed for courseId={}, falling back to MySQL", courseId, exception);
            return CourseCacheLookup.miss();
        }
    }

    public void put(Long courseId, CourseDetailResponse course) {
        try {
            String value = objectMapper.writeValueAsString(course);
            redisTemplate.opsForValue().set(buildKey(courseId), value, COURSE_TTL);
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.warn("Redis write failed for courseId={}, response will still use MySQL data", courseId, exception);
        }
    }

    public void putNotFound(Long courseId) {
        try {
            redisTemplate.opsForValue().set(buildKey(courseId), NULL_VALUE, NULL_TTL);
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis null-cache write failed for courseId={}", courseId, exception);
        }
    }

    private String buildKey(Long courseId) {
        return KEY_PREFIX + courseId;
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to delete invalid Redis key={}", key, exception);
        }
    }
}
