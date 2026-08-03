ALTER TABLE course_comment
    DROP INDEX uk_comment_course_user,
    ADD COLUMN active_user_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE WHEN status = 1 THEN user_id ELSE NULL END
        ) STORED COMMENT '活动评价用户主键；软删除后自动为空' AFTER user_id,
    ADD UNIQUE KEY uk_comment_course_active_user (course_id, active_user_id);
