package com.courseinsight.server.cache;

import com.courseinsight.server.dto.CourseDetailResponse;

public record CourseCacheLookup(boolean hit, CourseDetailResponse course) {

    public static CourseCacheLookup miss() {
        return new CourseCacheLookup(false, null);
    }

    public static CourseCacheLookup found(CourseDetailResponse course) {
        return new CourseCacheLookup(true, course);
    }

    public static CourseCacheLookup notFound() {
        return new CourseCacheLookup(true, null);
    }
}
