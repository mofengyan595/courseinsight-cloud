ALTER TABLE analysis_task
    ADD COLUMN current_event_id CHAR(32) NULL
        COMMENT 'Current durable Outbox generation' AFTER dead_lettered_at,
    ADD COLUMN execution_token CHAR(32) NULL
        COMMENT 'Unique token of the active execution owner' AFTER current_event_id,
    ADD COLUMN lease_until DATETIME(3) NULL
        COMMENT 'Active execution lease expiry' AFTER execution_token,
    ADD KEY idx_analysis_task_expired_lease (status, lease_until, id);

UPDATE analysis_task task
SET task.current_event_id = (
    SELECT event.event_id
    FROM analysis_outbox_event event
    WHERE event.task_id = task.id
    ORDER BY event.id DESC
    LIMIT 1
)
WHERE EXISTS (
    SELECT 1
    FROM analysis_outbox_event event
    WHERE event.task_id = task.id
);

UPDATE analysis_task
SET lease_until = CURRENT_TIMESTAMP(3)
WHERE status = 'PROCESSING'
  AND lease_until IS NULL;
