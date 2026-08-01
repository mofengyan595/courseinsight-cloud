CREATE TABLE course_comment
(
    id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    course_id  BIGINT UNSIGNED  NOT NULL COMMENT '课程主键',
    content    VARCHAR(2000)    NOT NULL COMMENT '评价内容',
    rating     TINYINT UNSIGNED NOT NULL COMMENT '评分：1-5',
    status     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：0-隐藏，1-正常',
    created_at DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),
    KEY idx_comment_course_created (course_id, created_at, id),
    CONSTRAINT chk_comment_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_comment_status CHECK (status IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '课程评价表';
