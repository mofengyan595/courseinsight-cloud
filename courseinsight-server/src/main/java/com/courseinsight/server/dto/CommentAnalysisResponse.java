package com.courseinsight.server.dto;

public record CommentAnalysisResponse(
        CommentDetailResponse comment,
        AnalysisTaskDetailResponse task,
        AnalysisResultResponse result
) {
}
