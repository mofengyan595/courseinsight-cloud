package com.courseinsight.server.service;

import com.courseinsight.server.cache.CoursePopularityRankingCache;
import com.courseinsight.server.cache.CoursePopularityRankingCacheLookup;
import com.courseinsight.server.cache.CoursePopularityRankingEntry;
import com.courseinsight.server.dto.CoursePopularityAggregate;
import com.courseinsight.server.dto.CoursePopularityRankingItemResponse;
import com.courseinsight.server.dto.CourseRankingQuery;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.mapper.CourseMapper;
import com.courseinsight.server.mapper.CoursePopularityRankingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CoursePopularityRankingServiceTests {

    @Mock
    private CoursePopularityRankingMapper rankingMapper;

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private CoursePopularityRankingCache rankingCache;

    @InjectMocks
    private CoursePopularityRankingService rankingService;

    @Test
    void shouldBuildRankingFromMysqlWhenCacheMisses() {
        given(rankingCache.get(10)).willReturn(CoursePopularityRankingCacheLookup.miss());
        given(rankingMapper.selectTopByCommentCount(
                CoursePopularityRankingService.CACHE_CAPACITY
        )).willReturn(List.of(aggregate(14L, 6L)));
        given(courseMapper.selectBatchIds(List.of(14L)))
                .willReturn(List.of(course(14L)));

        List<CoursePopularityRankingItemResponse> result =
                rankingService.getPopular(new CourseRankingQuery(10));

        assertThat(result).containsExactly(new CoursePopularityRankingItemResponse(
                1,
                14L,
                "AI-TEST-001",
                "Java后端开发",
                "测试教师",
                6
        ));
        verify(rankingCache).put(List.of(new CoursePopularityRankingEntry(14L, 6)));
    }

    @Test
    void shouldUseRedisRankingWithoutRunningAggregateQuery() {
        given(rankingCache.get(10)).willReturn(
                CoursePopularityRankingCacheLookup.found(List.of(
                        new CoursePopularityRankingEntry(14L, 6)
                ))
        );
        given(courseMapper.selectBatchIds(List.of(14L)))
                .willReturn(List.of(course(14L)));

        List<CoursePopularityRankingItemResponse> result =
                rankingService.getPopular(new CourseRankingQuery(10));

        assertThat(result).singleElement()
                .extracting(CoursePopularityRankingItemResponse::commentCount)
                .isEqualTo(6L);
        verifyNoInteractions(rankingMapper);
    }

    private CoursePopularityAggregate aggregate(Long courseId, Long commentCount) {
        CoursePopularityAggregate aggregate = new CoursePopularityAggregate();
        aggregate.setCourseId(courseId);
        aggregate.setCommentCount(commentCount);
        return aggregate;
    }

    private Course course(Long id) {
        Course course = new Course();
        course.setId(id);
        course.setCode("AI-TEST-001");
        course.setName("Java后端开发");
        course.setTeacherName("测试教师");
        course.setStatus(1);
        return course;
    }
}
