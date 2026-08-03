package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.cache.CourseAnalyticsCacheLookup;
import com.courseinsight.server.dto.CourseAnalyticsAggregate;
import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.CourseAnalyticsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseAnalyticsService {

    private final CourseManagementAccessService managementAccessService;
    private final CourseAnalyticsMapper courseAnalyticsMapper;
    private final CourseAnalyticsCache courseAnalyticsCache;

    public CourseAnalyticsService(
            CourseManagementAccessService managementAccessService,
            CourseAnalyticsMapper courseAnalyticsMapper,
            CourseAnalyticsCache courseAnalyticsCache) {
        this.managementAccessService = managementAccessService;
        this.courseAnalyticsMapper = courseAnalyticsMapper;
        this.courseAnalyticsCache = courseAnalyticsCache;
    }

    @Transactional(readOnly = true)
    public CourseAnalyticsSummaryResponse getSummary(
            Long courseId,
            Long currentUserId,
            UserRole currentRole) {
        managementAccessService.assertCanManage(
                courseId,
                currentUserId,
                currentRole
        );

        CourseAnalyticsCacheLookup cacheLookup = courseAnalyticsCache.get(courseId);
        if (cacheLookup.hit()) {
            return cacheLookup.summary();
        }

        CourseAnalyticsAggregate aggregate = courseAnalyticsMapper.selectSummary(courseId);
        if (aggregate == null) {
            throw new IllegalStateException("课程分析统计查询未返回结果");
        }
        CourseAnalyticsSummaryResponse summary = CourseAnalyticsSummaryResponse.from(
                courseId,
                aggregate
        );
        courseAnalyticsCache.put(courseId, summary);
        return summary;
    }
}
