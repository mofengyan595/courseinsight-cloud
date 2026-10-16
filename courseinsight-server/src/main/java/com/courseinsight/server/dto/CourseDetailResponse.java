package com.courseinsight.server.dto;

import com.courseinsight.server.entity.Course;

import java.time.LocalDateTime;

public record CourseDetailResponse(
        Long id,
        String code,
        String name,
        String teacherName,
        String description,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long ownerUserId
) {
    public CourseDetailResponse(Long id, String code, String name, String teacherName,
            String description, Integer status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, code, name, teacherName, description, status, createdAt, updatedAt, null);
    }

    public static CourseDetailResponse from(Course course) {
        return new CourseDetailResponse(
                course.getId(),
                course.getCode(),
                course.getName(),
                course.getTeacherName(),
                course.getDescription(),
                course.getStatus(),
                course.getCreatedAt(),
                course.getUpdatedAt(),
                course.getOwnerUserId()
        );
    }
}
