ALTER TABLE analysis_outbox_event
    ADD KEY idx_analysis_outbox_sent_retention (status, sent_at, id);
