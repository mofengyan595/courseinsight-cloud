ALTER TABLE analysis_task
    ADD COLUMN dead_lettered_at DATETIME(3) NULL COMMENT '进入 RocketMQ 死信队列时间' AFTER completed_at,
    ADD KEY idx_analysis_task_dead_lettered (status, dead_lettered_at, id);
