package com.courseinsight.server.cache;

import java.util.List;

public record CoursePopularityRankingCacheLookup(
        boolean hit,
        List<CoursePopularityRankingEntry> entries) {

    public static CoursePopularityRankingCacheLookup miss() {
        return new CoursePopularityRankingCacheLookup(false, List.of());
    }

    public static CoursePopularityRankingCacheLookup found(
            List<CoursePopularityRankingEntry> entries) {
        return new CoursePopularityRankingCacheLookup(true, List.copyOf(entries));
    }
}
