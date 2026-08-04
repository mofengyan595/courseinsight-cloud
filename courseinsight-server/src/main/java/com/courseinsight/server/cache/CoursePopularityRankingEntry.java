package com.courseinsight.server.cache;

public record CoursePopularityRankingEntry(
        Long courseId,
        long commentCount) {
}
