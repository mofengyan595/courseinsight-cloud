package com.courseinsight.server.dto;

public record AnalysisBatchCommentRow(
        long rowNumber,
        String content,
        int rating) {
}
