package com.courseinsight.server.dto;

import com.courseinsight.server.entity.CourseComment;

import java.time.LocalDateTime;

public record CommentDetailResponse(
        Long id,
        Long courseId,
        String content,
        Integer rating,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static CommentDetailResponse from(CourseComment comment) {
        return new CommentDetailResponse(
                comment.getId(),
                comment.getCourseId(),
                comment.getContent(),
                comment.getRating(),
                comment.getStatus(),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
}
