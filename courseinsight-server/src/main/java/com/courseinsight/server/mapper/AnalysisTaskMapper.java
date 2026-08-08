package com.courseinsight.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.courseinsight.server.entity.AnalysisTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    @Select("""
            SELECT id, task_no, comment_id, course_id, batch_id, status,
                   retry_count, failure_reason, started_at, completed_at,
                   dead_lettered_at, current_event_id, execution_token,
                   lease_until, created_at, updated_at
            FROM analysis_task
            WHERE batch_id = #{batchId}
              AND status = 'FAILED'
              AND dead_lettered_at IS NOT NULL
            ORDER BY id ASC
            FOR UPDATE
            """)
    List<AnalysisTask> selectDeadLetteredByBatchIdForUpdate(
            @Param("batchId") Long batchId);

    @Update("""
            UPDATE analysis_task
            SET status = 'PROCESSING',
                execution_token = #{executionToken},
                lease_until = #{leaseUntil},
                failure_reason = NULL,
                started_at = #{startedAt},
                completed_at = NULL
            WHERE id = #{taskId}
              AND current_event_id = #{eventId}
              AND dead_lettered_at IS NULL
              AND (
                    status IN ('WAITING', 'FAILED')
                    OR (status = 'PROCESSING' AND lease_until <= #{startedAt})
                  )
            """)
    int claimForEvent(
            @Param("taskId") Long taskId,
            @Param("eventId") String eventId,
            @Param("executionToken") String executionToken,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE analysis_task
            SET status = 'PROCESSING',
                execution_token = #{executionToken},
                lease_until = #{leaseUntil},
                failure_reason = NULL,
                started_at = #{startedAt},
                completed_at = NULL,
                dead_lettered_at = NULL
            WHERE id = #{taskId}
              AND current_event_id <=> #{expectedEventId}
              AND (
                    status IN ('WAITING', 'FAILED')
                    OR (status = 'PROCESSING' AND lease_until <= #{startedAt})
                  )
            """)
    int claimManually(
            @Param("taskId") Long taskId,
            @Param("expectedEventId") String expectedEventId,
            @Param("executionToken") String executionToken,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE analysis_task
            SET status = 'SUCCESS',
                failure_reason = NULL,
                completed_at = #{completedAt},
                execution_token = NULL,
                lease_until = NULL
            WHERE id = #{taskId}
              AND status = 'PROCESSING'
              AND current_event_id <=> #{eventId}
              AND execution_token = #{executionToken}
            """)
    int completeOwnedExecution(
            @Param("taskId") Long taskId,
            @Param("eventId") String eventId,
            @Param("executionToken") String executionToken,
            @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE analysis_task
            SET status = 'FAILED',
                failure_reason = #{failureReason},
                completed_at = #{completedAt},
                dead_lettered_at = #{deadLetteredAt},
                execution_token = NULL,
                lease_until = NULL,
                retry_count = retry_count + 1
            WHERE id = #{taskId}
              AND status = 'PROCESSING'
              AND current_event_id <=> #{eventId}
              AND execution_token = #{executionToken}
            """)
    int failOwnedExecution(
            @Param("taskId") Long taskId,
            @Param("eventId") String eventId,
            @Param("executionToken") String executionToken,
            @Param("failureReason") String failureReason,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("deadLetteredAt") LocalDateTime deadLetteredAt);

    @Update("""
            UPDATE analysis_task
            SET status = 'FAILED',
                completed_at = #{deadLetteredAt},
                dead_lettered_at = #{deadLetteredAt},
                execution_token = NULL,
                lease_until = NULL
            WHERE id = #{taskId}
              AND current_event_id = #{eventId}
              AND status <> 'SUCCESS'
              AND dead_lettered_at IS NULL
              AND (
                    status <> 'PROCESSING'
                    OR lease_until IS NULL
                    OR lease_until <= #{deadLetteredAt}
                  )
            """)
    int markCurrentGenerationDeadLettered(
            @Param("taskId") Long taskId,
            @Param("eventId") String eventId,
            @Param("deadLetteredAt") LocalDateTime deadLetteredAt);

    @Update("""
            UPDATE analysis_task
            SET status = 'WAITING',
                current_event_id = #{newEventId},
                execution_token = NULL,
                lease_until = NULL,
                failure_reason = NULL,
                started_at = NULL,
                completed_at = NULL,
                dead_lettered_at = NULL
            WHERE id = #{taskId}
              AND current_event_id <=> #{expectedEventId}
              AND status IN ('WAITING', 'FAILED')
            """)
    int requeueWithNewGeneration(
            @Param("taskId") Long taskId,
            @Param("expectedEventId") String expectedEventId,
            @Param("newEventId") String newEventId);

    @Update("""
            UPDATE analysis_task
            SET status = 'WAITING',
                current_event_id = #{newEventId},
                execution_token = NULL,
                lease_until = NULL,
                failure_reason = NULL,
                started_at = NULL,
                completed_at = NULL,
                dead_lettered_at = NULL
            WHERE id = #{taskId}
              AND current_event_id <=> #{expectedEventId}
              AND status = 'FAILED'
              AND dead_lettered_at IS NOT NULL
            """)
    int recoverDeadLetteredWithNewGeneration(
            @Param("taskId") Long taskId,
            @Param("expectedEventId") String expectedEventId,
            @Param("newEventId") String newEventId);

    @Select("""
            SELECT id, task_no, comment_id, course_id, batch_id, status,
                   retry_count, failure_reason, started_at, completed_at,
                   dead_lettered_at, current_event_id, execution_token,
                   lease_until, created_at, updated_at
            FROM analysis_task
            WHERE status = 'PROCESSING'
              AND lease_until <= #{now}
            ORDER BY lease_until ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<AnalysisTask> selectExpiredExecutionsForUpdate(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    @Update("""
            UPDATE analysis_task
            SET status = 'WAITING',
                current_event_id = #{newEventId},
                execution_token = NULL,
                lease_until = NULL,
                failure_reason = NULL,
                started_at = NULL,
                completed_at = NULL,
                dead_lettered_at = NULL
            WHERE id = #{taskId}
              AND status = 'PROCESSING'
              AND current_event_id <=> #{expectedEventId}
              AND execution_token <=> #{executionToken}
              AND lease_until <= #{now}
            """)
    int recoverExpiredExecution(
            @Param("taskId") Long taskId,
            @Param("expectedEventId") String expectedEventId,
            @Param("executionToken") String executionToken,
            @Param("newEventId") String newEventId,
            @Param("now") LocalDateTime now);
}
