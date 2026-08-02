CREATE TABLE analysis_result
(
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id              BIGINT UNSIGNED NOT NULL COMMENT 'Analysis task primary key',
    comment_id           BIGINT UNSIGNED NOT NULL COMMENT 'Course comment primary key',
    course_id            BIGINT UNSIGNED NOT NULL COMMENT 'Course primary key',
    language             VARCHAR(10)     NOT NULL COMMENT 'Detected language',
    sentiment            VARCHAR(20)     NOT NULL COMMENT 'Sentiment label',
    confidence           DECIMAL(6, 5)   NOT NULL COMMENT 'Sentiment confidence',
    sentiment_source     VARCHAR(30)     NOT NULL COMMENT 'Sentiment analysis source',
    sentiment_device     VARCHAR(20)     NOT NULL COMMENT 'Inference device',
    topics_json          JSON            NOT NULL COMMENT 'Detected topics',
    topic_evidence_json  JSON            NOT NULL COMMENT 'Topic evidence',
    keywords_json        JSON            NOT NULL COMMENT 'Extracted keywords',
    long_text_handled    TINYINT(1)      NOT NULL COMMENT 'Whether long-text handling ran',
    long_text_truncated  TINYINT(1)      NOT NULL COMMENT 'Whether input was truncated',
    advice_json          JSON                     COMMENT 'Structured teaching advice',
    risk_level           VARCHAR(20)              COMMENT 'Advice risk level',
    advice_source        VARCHAR(30)              COMMENT 'Advice generation source',
    created_at           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_result_task (task_id),
    UNIQUE KEY uk_analysis_result_comment (comment_id),
    KEY idx_analysis_result_course_created (course_id, created_at, id),
    KEY idx_analysis_result_course_sentiment (course_id, sentiment, created_at, id),
    CONSTRAINT chk_analysis_result_sentiment
        CHECK (sentiment IN ('positive', 'neutral', 'negative')),
    CONSTRAINT chk_analysis_result_confidence
        CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Course comment analysis result';
