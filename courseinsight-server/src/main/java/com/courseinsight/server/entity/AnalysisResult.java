package com.courseinsight.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("analysis_result")
public class AnalysisResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private Long commentId;
    private Long courseId;
    private String language;
    private String sentiment;
    private BigDecimal confidence;
    private String sentimentSource;
    private String sentimentDevice;
    private String topicsJson;
    private String topicEvidenceJson;
    private String keywordsJson;
    private Boolean longTextHandled;
    private Boolean longTextTruncated;
    private String adviceJson;
    private String riskLevel;
    private String adviceSource;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
