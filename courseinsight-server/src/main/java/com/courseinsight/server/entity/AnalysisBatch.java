package com.courseinsight.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("analysis_batch")
public class AnalysisBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;
    private Long courseId;
    private Long createdBy;
    private String originalFilename;
    private Integer totalCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
