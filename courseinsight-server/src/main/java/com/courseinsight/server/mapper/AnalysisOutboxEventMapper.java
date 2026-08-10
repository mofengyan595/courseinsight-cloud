package com.courseinsight.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AnalysisOutboxEventMapper extends BaseMapper<AnalysisOutboxEvent> {

    @Select("""
            SELECT id, event_id, task_id, comment_id, event_type, status,
                   publish_token, retry_count, next_retry_at, message_id,
                   failure_reason, sent_at, created_at, updated_at
            FROM analysis_outbox_event
            WHERE status IN ('PENDING', 'FAILED', 'PUBLISHING')
              AND next_retry_at <= CURRENT_TIMESTAMP(3)
            ORDER BY id ASC
            LIMIT #{batchSize}
            """)
    List<AnalysisOutboxEvent> selectPublishable(
            @Param("batchSize") int batchSize);

    @Update("""
            UPDATE analysis_outbox_event
            SET status = 'PUBLISHING',
                publish_token = #{publishToken},
                failure_reason = NULL,
                next_retry_at = TIMESTAMPADD(
                    SECOND,
                    #{recoveryTimeoutSeconds},
                    CURRENT_TIMESTAMP(3)
                )
            WHERE id = #{outboxId}
              AND status IN ('PENDING', 'FAILED', 'PUBLISHING')
              AND next_retry_at <= CURRENT_TIMESTAMP(3)
            """)
    int claimPublishAttempt(
            @Param("outboxId") Long outboxId,
            @Param("publishToken") String publishToken,
            @Param("recoveryTimeoutSeconds") long recoveryTimeoutSeconds);

    @Update("""
            UPDATE analysis_outbox_event
            SET status = 'SENT',
                publish_token = NULL,
                message_id = #{messageId},
                failure_reason = NULL,
                sent_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{outboxId}
              AND status = 'PUBLISHING'
              AND publish_token = #{publishToken}
            """)
    int markOwnedAttemptSent(
            @Param("outboxId") Long outboxId,
            @Param("publishToken") String publishToken,
            @Param("messageId") String messageId);

    @Update("""
            UPDATE analysis_outbox_event
            SET status = 'FAILED',
                publish_token = NULL,
                failure_reason = #{failureReason},
                next_retry_at = TIMESTAMPADD(
                    SECOND,
                    #{retryDelaySeconds},
                    CURRENT_TIMESTAMP(3)
                ),
                retry_count = retry_count + 1
            WHERE id = #{outboxId}
              AND status = 'PUBLISHING'
              AND publish_token = #{publishToken}
            """)
    int markOwnedAttemptFailed(
            @Param("outboxId") Long outboxId,
            @Param("publishToken") String publishToken,
            @Param("failureReason") String failureReason,
            @Param("retryDelaySeconds") long retryDelaySeconds);
}
