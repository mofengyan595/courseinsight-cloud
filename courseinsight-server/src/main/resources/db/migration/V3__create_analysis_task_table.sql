CREATE TABLE analysis_task
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_no        CHAR(32)        NOT NULL COMMENT '业务任务编号',
    comment_id     BIGINT UNSIGNED NOT NULL COMMENT '课程评价主键',
    course_id      BIGINT UNSIGNED NOT NULL COMMENT '课程主键',
    status         VARCHAR(20)     NOT NULL COMMENT '任务状态',
    retry_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '已重试次数',
    failure_reason VARCHAR(1000)            COMMENT '失败原因',
    started_at     DATETIME(3)              COMMENT '开始处理时间',
    completed_at   DATETIME(3)              COMMENT '处理完成时间',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_task_no (task_no),
    UNIQUE KEY uk_analysis_task_comment (comment_id),
    KEY idx_analysis_task_status_created (status, created_at, id),
    KEY idx_analysis_task_course_created (course_id, created_at, id),
    CONSTRAINT chk_analysis_task_status
        CHECK (status IN ('WAITING', 'PROCESSING', 'SUCCESS', 'FAILED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '评价分析任务表';
