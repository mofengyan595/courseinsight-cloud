package com.courseinsight.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CoursePageQuery(
        @Min(value = 1, message = "页码必须大于等于1")
        @Schema(defaultValue = "1") Integer page,

        @Min(value = 1, message = "每页条数必须大于等于1")
        @Max(value = 100, message = "每页条数不能超过100")
        @Schema(defaultValue = "10") Integer size,

        @Size(max = 100, message = "搜索关键词不能超过100个字符")
        String keyword
) {

    public CoursePageQuery {
        page = page == null ? 1 : page;
        size = size == null ? 10 : size;
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
