ALTER TABLE course_comment
    ADD COLUMN user_id BIGINT UNSIGNED NULL COMMENT '评价用户主键；历史数据可为空' AFTER course_id,
    ADD COLUMN is_anonymous TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否匿名展示：0-否，1-是' AFTER rating,
    ADD UNIQUE KEY uk_comment_course_user (course_id, user_id),
    ADD KEY idx_comment_user_created (user_id, created_at, id),
    ADD CONSTRAINT chk_comment_anonymous CHECK (is_anonymous IN (0, 1));
