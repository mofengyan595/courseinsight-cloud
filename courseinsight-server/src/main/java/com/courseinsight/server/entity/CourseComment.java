package com.courseinsight.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("course_comment")
public class CourseComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;
    private Long userId;
    private String content;
    private Integer rating;

    @TableField("is_anonymous")
    private Boolean anonymous;

    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
