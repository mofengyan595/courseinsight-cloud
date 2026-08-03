package com.courseinsight.server.service;

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

    public CourseAnalyticsService(
            CourseManagementAccessService managementAccessService,
            CourseAnalyticsMapper courseAnalyticsMapper) {
        this.managementAccessService = managementAccessService;
        this.courseAnalyticsMapper = courseAnalyticsMapper;
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
        CourseAnalyticsAggregate aggregate = courseAnalyticsMapper.selectSummary(courseId);
        if (aggregate == null) {
            throw new IllegalStateException("课程分析统计查询未返回结果");
        }
        return CourseAnalyticsSummaryResponse.from(courseId, aggregate);
    }
}
