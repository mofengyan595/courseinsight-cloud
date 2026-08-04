package com.courseinsight.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CourseRankingQuery(
        @Min(value = 1, message = "排行榜数量必须大于等于1")
        @Max(value = 50, message = "排行榜数量不能超过50")
        Integer limit) {

    public CourseRankingQuery {
        limit = limit == null ? 10 : limit;
    }
}
