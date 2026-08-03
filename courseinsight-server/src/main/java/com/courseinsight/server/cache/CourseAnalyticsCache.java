package com.courseinsight.server.cache;

import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

@Component
public class CourseAnalyticsCache {

    static final String KEY_PREFIX = "course:analytics:summary:";
    static final Duration SUMMARY_TTL = Duration.ofMinutes(5);

    private static final Logger LOGGER = LoggerFactory.getLogger(CourseAnalyticsCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CourseAnalyticsCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public CourseAnalyticsCacheLookup get(Long courseId) {
        String key = buildKey(courseId);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return CourseAnalyticsCacheLookup.miss();
            }
            return CourseAnalyticsCacheLookup.found(
                    objectMapper.readValue(value, CourseAnalyticsSummaryResponse.class)
            );
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Invalid course analytics cache for courseId={}", courseId, exception);
            deleteQuietly(key);
            return CourseAnalyticsCacheLookup.miss();
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Redis analytics read failed for courseId={}, falling back to MySQL",
                    courseId,
                    exception
            );
            return CourseAnalyticsCacheLookup.miss();
        }
    }

    public void put(Long courseId, CourseAnalyticsSummaryResponse summary) {
        try {
            String value = objectMapper.writeValueAsString(summary);
            redisTemplate.opsForValue().set(buildKey(courseId), value, SUMMARY_TTL);
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.warn(
                    "Redis analytics write failed for courseId={}, response still uses MySQL data",
                    courseId,
                    exception
            );
        }
    }

    public void evict(Long courseId) {
        deleteQuietly(buildKey(courseId));
    }

    public void evictAfterCommit(Long courseId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(courseId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        evict(courseId);
                    }
                }
        );
    }

    private String buildKey(Long courseId) {
        return KEY_PREFIX + courseId;
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to delete Redis key={}", key, exception);
        }
    }
}
