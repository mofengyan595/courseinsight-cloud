package com.courseinsight.server.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CoursePopularityRankingCacheTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private CoursePopularityRankingCache rankingCache;

    @BeforeEach
    void setUp() {
        rankingCache = new CoursePopularityRankingCache(redisTemplate);
    }

    @Test
    void shouldReturnMissWhenRankingIsNotReady() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(CoursePopularityRankingCache.READY_KEY)).willReturn(null);

        CoursePopularityRankingCacheLookup result = rankingCache.get(10);

        assertThat(result.hit()).isFalse();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void shouldReadRankingInDescendingScoreOrder() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(CoursePopularityRankingCache.READY_KEY))
                .willReturn(CoursePopularityRankingCache.READY_VALUE);
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.reverseRangeWithScores(
                CoursePopularityRankingCache.RANKING_KEY,
                0,
                9
        )).willReturn(Set.of(
                ZSetOperations.TypedTuple.of("00000000000000000014", 6.0)
        ));

        CoursePopularityRankingCacheLookup result = rankingCache.get(10);

        assertThat(result.hit()).isTrue();
        assertThat(result.entries()).containsExactly(
                new CoursePopularityRankingEntry(14L, 6)
        );
    }

    @Test
    void shouldPublishRankingAndReadyMarkerWithSingleScript() {
        rankingCache.put(List.of(new CoursePopularityRankingEntry(14L, 6)));

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        CoursePopularityRankingCache.RANKING_KEY,
                        CoursePopularityRankingCache.READY_KEY
                )),
                eq("600000"),
                eq("601000"),
                eq(CoursePopularityRankingCache.READY_VALUE),
                eq("6"),
                eq("00000000000000000014")
        );
    }

    @Test
    void shouldEvictImmediatelyWithoutTransaction() {
        rankingCache.evictAfterCommit();

        verify(redisTemplate).delete(List.of(
                CoursePopularityRankingCache.RANKING_KEY,
                CoursePopularityRankingCache.READY_KEY
        ));
    }
}
