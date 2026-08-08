CREATE TABLE analysis_batch
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_no          CHAR(32)        NOT NULL COMMENT '批次业务编号',
    course_id         BIGINT UNSIGNED NOT NULL COMMENT '课程主键',
    created_by        BIGINT UNSIGNED NOT NULL COMMENT '上传用户主键',
    original_filename VARCHAR(255)    NOT NULL COMMENT '原始 CSV 文件名',
    total_count       INT UNSIGNED    NOT NULL COMMENT '批次评价总数',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_batch_no (batch_no),
    KEY idx_analysis_batch_course_created (course_id, created_at, id),
    KEY idx_analysis_batch_creator_created (created_by, created_at, id),
    CONSTRAINT chk_analysis_batch_total_count
        CHECK (total_count BETWEEN 1 AND 200)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '批量评价分析批次表';

ALTER TABLE analysis_task
    ADD COLUMN batch_id BIGINT UNSIGNED NULL COMMENT '批量分析批次主键' AFTER course_id,
    ADD KEY idx_analysis_task_batch_status (batch_id, status, id);
