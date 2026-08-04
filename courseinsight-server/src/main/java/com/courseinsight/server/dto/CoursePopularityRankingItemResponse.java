package com.courseinsight.server.dto;

public record CoursePopularityRankingItemResponse(
        int rank,
        Long courseId,
        String courseCode,
        String courseName,
        String teacherName,
        long commentCount) {
}
