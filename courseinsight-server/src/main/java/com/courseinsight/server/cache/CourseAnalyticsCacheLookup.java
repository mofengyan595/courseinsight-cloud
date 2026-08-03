package com.courseinsight.server.cache;

import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;

public record CourseAnalyticsCacheLookup(
        boolean hit,
        CourseAnalyticsSummaryResponse summary) {

    public static CourseAnalyticsCacheLookup miss() {
        return new CourseAnalyticsCacheLookup(false, null);
    }

    public static CourseAnalyticsCacheLookup found(
            CourseAnalyticsSummaryResponse summary) {
        return new CourseAnalyticsCacheLookup(true, summary);
    }
}
