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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CoursePopularityRankingService {

    static final int CACHE_CAPACITY = 50;

    private final CoursePopularityRankingMapper rankingMapper;
    private final CourseMapper courseMapper;
    private final CoursePopularityRankingCache rankingCache;

    public CoursePopularityRankingService(
            CoursePopularityRankingMapper rankingMapper,
            CourseMapper courseMapper,
            CoursePopularityRankingCache rankingCache) {
        this.rankingMapper = rankingMapper;
        this.courseMapper = courseMapper;
        this.rankingCache = rankingCache;
    }

    @Transactional(readOnly = true)
    public List<CoursePopularityRankingItemResponse> getPopular(
            CourseRankingQuery query) {
        CoursePopularityRankingCacheLookup cacheLookup = rankingCache.get(query.limit());
        List<CoursePopularityRankingEntry> entries;

        if (cacheLookup.hit()) {
            entries = cacheLookup.entries();
        } else {
            entries = rankingMapper.selectTopByCommentCount(CACHE_CAPACITY)
                    .stream()
                    .map(this::toCacheEntry)
                    .toList();
            rankingCache.put(entries);
            entries = entries.stream().limit(query.limit()).toList();
        }

        if (entries.isEmpty()) {
            return List.of();
        }

        Map<Long, Course> coursesById = new HashMap<>();
        for (Course course : courseMapper.selectBatchIds(
                entries.stream().map(CoursePopularityRankingEntry::courseId).toList())) {
            coursesById.put(course.getId(), course);
        }

        List<CoursePopularityRankingItemResponse> response = new ArrayList<>();
        for (CoursePopularityRankingEntry entry : entries) {
            Course course = coursesById.get(entry.courseId());
            if (course == null || !Integer.valueOf(1).equals(course.getStatus())) {
                continue;
            }
            response.add(new CoursePopularityRankingItemResponse(
                    response.size() + 1,
                    course.getId(),
                    course.getCode(),
                    course.getName(),
                    course.getTeacherName(),
                    entry.commentCount()
            ));
        }
        return List.copyOf(response);
    }

    private CoursePopularityRankingEntry toCacheEntry(
            CoursePopularityAggregate aggregate) {
        return new CoursePopularityRankingEntry(
                aggregate.getCourseId(),
                aggregate.getCommentCount()
        );
    }
}
