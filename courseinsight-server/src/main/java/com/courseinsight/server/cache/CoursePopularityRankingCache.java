package com.courseinsight.server.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class CoursePopularityRankingCache {

    static final String RANKING_KEY = "course:ranking:popular";
    static final String READY_KEY = "course:ranking:popular:ready";
    static final String READY_VALUE = "1";
    static final Duration RANKING_TTL = Duration.ofMinutes(10);

    private static final Logger LOGGER =
            LoggerFactory.getLogger(CoursePopularityRankingCache.class);

    private final StringRedisTemplate redisTemplate;

    public CoursePopularityRankingCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public CoursePopularityRankingCacheLookup get(int limit) {
        try {
            String ready = redisTemplate.opsForValue().get(READY_KEY);
            if (!READY_VALUE.equals(ready)) {
                return CoursePopularityRankingCacheLookup.miss();
            }

            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate
                    .opsForZSet()
                    .reverseRangeWithScores(RANKING_KEY, 0, limit - 1L);
            if (tuples == null) {
                return CoursePopularityRankingCacheLookup.miss();
            }

            List<CoursePopularityRankingEntry> entries = tuples.stream()
                    .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                    .map(tuple -> new CoursePopularityRankingEntry(
                            parseCourseId(tuple.getValue()),
                            tuple.getScore().longValue()
                    ))
                    .toList();
            return CoursePopularityRankingCacheLookup.found(entries);
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis course ranking read failed, falling back to MySQL", exception);
            return CoursePopularityRankingCacheLookup.miss();
        }
    }

    public void put(List<CoursePopularityRankingEntry> entries) {
        try {
            redisTemplate.delete(RANKING_KEY);
            if (!entries.isEmpty()) {
                Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
                for (CoursePopularityRankingEntry entry : entries) {
                    tuples.add(new DefaultTypedTuple<>(
                            formatCourseId(entry.courseId()),
                            (double) entry.commentCount()
                    ));
                }
                redisTemplate.opsForZSet().add(RANKING_KEY, tuples);
                redisTemplate.expire(RANKING_KEY, RANKING_TTL);
            }
            redisTemplate.opsForValue().set(READY_KEY, READY_VALUE, RANKING_TTL);
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis course ranking write failed, response still uses MySQL data", exception);
        }
    }

    public void evict() {
        try {
            redisTemplate.delete(List.of(RANKING_KEY, READY_KEY));
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to delete Redis course ranking", exception);
        }
    }

    public void evictAfterCommit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            evict();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        evict();
                    }
                }
        );
    }

    private String formatCourseId(Long courseId) {
        return String.format("%020d", courseId);
    }

    private Long parseCourseId(String member) {
        return Long.parseLong(member);
    }
}
