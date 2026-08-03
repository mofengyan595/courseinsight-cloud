package com.courseinsight.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("analysis_outbox_event")
public class AnalysisOutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;
    private Long taskId;
    private Long commentId;
    private String eventType;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String messageId;
    private String failureReason;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
