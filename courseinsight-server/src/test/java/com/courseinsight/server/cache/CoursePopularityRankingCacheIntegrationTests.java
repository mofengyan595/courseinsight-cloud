package com.courseinsight.server.cache;

import com.courseinsight.server.testsupport.RedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@RedisIntegrationTest
class CoursePopularityRankingCacheIntegrationTests {

    @Autowired
    private CoursePopularityRankingCache rankingCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRanking() {
        redisTemplate.delete(List.of(
                CoursePopularityRankingCache.RANKING_KEY,
                CoursePopularityRankingCache.READY_KEY
        ));
    }

    @Test
    void shouldAtomicallyPublishNonEmptyRankingWithSafeTtlRelationship() {
        rankingCache.put(List.of(
                new CoursePopularityRankingEntry(14L, 6),
                new CoursePopularityRankingEntry(15L, 9)
        ));

        assertThat(redisTemplate.opsForValue().get(
                CoursePopularityRankingCache.READY_KEY
        )).isEqualTo(CoursePopularityRankingCache.READY_VALUE);
        assertThat(rankingCache.get(10).entries()).containsExactly(
                new CoursePopularityRankingEntry(15L, 9),
                new CoursePopularityRankingEntry(14L, 6)
        );

        Long readyTtl = redisTemplate.getExpire(
                CoursePopularityRankingCache.READY_KEY,
                TimeUnit.MILLISECONDS
        );
        Long rankingTtl = redisTemplate.getExpire(
                CoursePopularityRankingCache.RANKING_KEY,
                TimeUnit.MILLISECONDS
        );
        assertThat(readyTtl).isPositive();
        assertThat(rankingTtl).isGreaterThan(readyTtl);
    }

    @Test
    void shouldCacheAnIntentionallyEmptyRanking() {
        rankingCache.put(List.of());

        CoursePopularityRankingCacheLookup lookup = rankingCache.get(10);
        assertThat(lookup.hit()).isTrue();
        assertThat(lookup.entries()).isEmpty();
        assertThat(redisTemplate.hasKey(
                CoursePopularityRankingCache.READY_KEY
        )).isTrue();
        assertThat(redisTemplate.hasKey(
                CoursePopularityRankingCache.RANKING_KEY
        )).isFalse();
    }

    @Test
    void shouldFullyReplacePreviousRanking() {
        rankingCache.put(List.of(
                new CoursePopularityRankingEntry(10L, 5),
                new CoursePopularityRankingEntry(11L, 4)
        ));

        rankingCache.put(List.of(new CoursePopularityRankingEntry(20L, 8)));

        assertThat(rankingCache.get(10).entries()).containsExactly(
                new CoursePopularityRankingEntry(20L, 8)
        );
        assertThat(redisTemplate.opsForZSet().range(
                CoursePopularityRankingCache.RANKING_KEY,
                0,
                -1
        )).containsExactly("00000000000000000020");
    }

    @Test
    void shouldLeaveOneCompleteRankingAfterConcurrentPublications()
            throws Exception {
        List<List<CoursePopularityRankingEntry>> publications = new ArrayList<>();
        List<List<CoursePopularityRankingEntry>> expectedReads = new ArrayList<>();
        for (long index = 0; index < 8; index++) {
            List<CoursePopularityRankingEntry> entries = List.of(
                    new CoursePopularityRankingEntry(100L + index * 10, index + 1),
                    new CoursePopularityRankingEntry(101L + index * 10, index + 2)
            );
            publications.add(entries);
            expectedReads.add(List.of(entries.get(1), entries.get(0)));
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (List<CoursePopularityRankingEntry> publication : publications) {
                futures.add(executor.submit(() -> rankingCache.put(publication)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        CoursePopularityRankingCacheLookup lookup = rankingCache.get(10);
        assertThat(lookup.hit()).isTrue();
        assertThat(expectedReads).contains(lookup.entries());
        assertThat(redisTemplate.opsForValue().get(
                CoursePopularityRankingCache.READY_KEY
        )).isEqualTo(CoursePopularityRankingCache.READY_VALUE);
        assertThat(redisTemplate.opsForZSet().size(
                CoursePopularityRankingCache.RANKING_KEY
        )).isEqualTo(2);
    }
}
