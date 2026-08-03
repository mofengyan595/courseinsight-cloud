CREATE TABLE app_user
(
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(32)      NOT NULL COMMENT '登录用户名',
    password_hash VARCHAR(255)     NOT NULL COMMENT '密码哈希',
    display_name  VARCHAR(50)      NOT NULL COMMENT '显示名称',
    role          VARCHAR(20)      NOT NULL DEFAULT 'STUDENT' COMMENT '用户角色',
    status        TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    created_at    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username),
    KEY idx_app_user_role_status (role, status),
    CONSTRAINT chk_app_user_role
        CHECK (role IN ('STUDENT', 'TEACHER', 'ADMIN')),
    CONSTRAINT chk_app_user_status CHECK (status IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '平台用户表';
