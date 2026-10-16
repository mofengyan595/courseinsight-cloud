package com.courseinsight.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AnalysisBatchPageQuery(
        @Min(value = 1, message = "页码必须大于等于1") Integer page,
        @Min(value = 1, message = "每页条数必须大于等于1")
        @Max(value = 100, message = "每页条数不能超过100") Integer size
) {
    public AnalysisBatchPageQuery {
        page = page == null ? 1 : page;
        size = size == null ? 10 : size;
    }
}
