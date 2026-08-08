ALTER TABLE analysis_outbox_event
    DROP INDEX uk_analysis_outbox_task,
    ADD KEY idx_analysis_outbox_task_created (task_id, created_at, id);
