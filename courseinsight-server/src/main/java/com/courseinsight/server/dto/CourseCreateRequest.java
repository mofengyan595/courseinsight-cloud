package com.courseinsight.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CourseCreateRequest(
        @NotBlank(message = "课程代码不能为空")
        @Size(max = 32, message = "课程代码不能超过32个字符")
        String code,

        @NotBlank(message = "课程名称不能为空")
        @Size(max = 100, message = "课程名称不能超过100个字符")
        String name,

        @NotBlank(message = "教师姓名不能为空")
        @Size(max = 50, message = "教师姓名不能超过50个字符")
        String teacherName,

        @Size(max = 500, message = "课程简介不能超过500个字符")
        String description
) {
}