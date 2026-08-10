ALTER TABLE analysis_outbox_event
    ADD COLUMN publish_token CHAR(32) NULL
        COMMENT 'Current publisher attempt ownership token'
        AFTER status;
