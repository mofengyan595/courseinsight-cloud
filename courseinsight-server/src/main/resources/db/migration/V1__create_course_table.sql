CREATE TABLE course
(
    id           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    code         VARCHAR(32)      NOT NULL COMMENT '课程代码',
    name         VARCHAR(100)     NOT NULL COMMENT '课程名称',
    teacher_name VARCHAR(50)      NOT NULL COMMENT '教师姓名',
    description  VARCHAR(500)              COMMENT '课程简介',
    status       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：0-停用，1-启用',
    created_at   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_course_code (code),
    KEY idx_course_name (name),
    CONSTRAINT chk_course_status CHECK (status IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '课程表';
