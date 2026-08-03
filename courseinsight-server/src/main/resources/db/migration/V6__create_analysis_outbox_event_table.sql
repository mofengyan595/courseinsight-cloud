CREATE TABLE analysis_outbox_event
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id       CHAR(32)        NOT NULL COMMENT '业务事件编号',
    task_id        BIGINT UNSIGNED NOT NULL COMMENT '分析任务主键',
    comment_id     BIGINT UNSIGNED NOT NULL COMMENT '课程评价主键',
    event_type     VARCHAR(50)     NOT NULL COMMENT '事件类型',
    status         VARCHAR(20)     NOT NULL COMMENT '发布状态',
    retry_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '发布重试次数',
    next_retry_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可发布时间',
    message_id     VARCHAR(128)             COMMENT 'RocketMQ 消息编号',
    failure_reason VARCHAR(1000)            COMMENT '最后一次失败原因',
    sent_at        DATETIME(3)              COMMENT '发布成功时间',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_outbox_event_id (event_id),
    UNIQUE KEY uk_analysis_outbox_task (task_id),
    KEY idx_analysis_outbox_dispatch (status, next_retry_at, id),
    CONSTRAINT chk_analysis_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'SENT', 'FAILED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '分析任务事件 Outbox 表';
