package com.courseinsight.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CommentCreateRequest(
        @NotBlank(message = "评价内容不能为空")
        @Size(max = 2000, message = "评价内容不能超过2000个字符")
        String content,

        @NotNull(message = "课程评分不能为空")
        @Min(value = 1, message = "课程评分不能小于1")
        @Max(value = 5, message = "课程评分不能大于5")
        Integer rating
) {

    public CommentCreateRequest {
        content = content == null ? null : content.trim();
    }
}
