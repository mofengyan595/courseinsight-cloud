ALTER TABLE course
    ADD COLUMN owner_user_id BIGINT UNSIGNED NULL
        COMMENT '课程管理者用户主键；历史数据可为空' AFTER teacher_name,
    ADD KEY idx_course_owner_status (owner_user_id, status);
