CREATE TEMPORARY TABLE v14_analysis_task_recovery_generation
(
    task_id  BIGINT UNSIGNED NOT NULL,
    event_id CHAR(32)        NOT NULL,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_v14_recovery_event_id (event_id)
) ENGINE = InnoDB;

INSERT INTO v14_analysis_task_recovery_generation (task_id, event_id)
SELECT task.id, REPLACE(UUID(), '-', '')
FROM analysis_task task
WHERE task.status IN ('WAITING', 'PROCESSING')
   OR (task.status = 'FAILED' AND task.dead_lettered_at IS NULL);

START TRANSACTION;

INSERT INTO analysis_outbox_event
    (event_id, task_id, comment_id, event_type, status,
     retry_count, next_retry_at)
SELECT recovery.event_id,
       task.id,
       task.comment_id,
       'COMMENT_ANALYSIS_CREATED',
       'PENDING',
       0,
       CURRENT_TIMESTAMP(3)
FROM v14_analysis_task_recovery_generation recovery
JOIN analysis_task task ON task.id = recovery.task_id;

UPDATE analysis_task task
JOIN v14_analysis_task_recovery_generation recovery
    ON recovery.task_id = task.id
SET task.status = 'WAITING',
    task.current_event_id = recovery.event_id,
    task.execution_token = NULL,
    task.lease_until = NULL,
    task.failure_reason = NULL,
    task.started_at = NULL,
    task.completed_at = NULL,
    task.dead_lettered_at = NULL;

DROP TEMPORARY TABLE v14_analysis_task_recovery_generation;

COMMIT;
