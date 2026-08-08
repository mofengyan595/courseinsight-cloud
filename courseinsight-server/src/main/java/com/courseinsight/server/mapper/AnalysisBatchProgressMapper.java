package com.courseinsight.server.mapper;

import com.courseinsight.server.dto.AnalysisBatchProgressAggregate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AnalysisBatchProgressMapper {

    @Select("""
            SELECT batch.id AS batch_id,
                   batch.batch_no,
                   batch.course_id,
                   batch.created_by,
                   batch.original_filename,
                   batch.total_count,
                   COALESCE(task_summary.waiting_count, 0) AS waiting_count,
                   COALESCE(task_summary.processing_count, 0) AS processing_count,
                   COALESCE(task_summary.retrying_count, 0) AS retrying_count,
                   COALESCE(task_summary.success_count, 0) AS success_count,
                   COALESCE(task_summary.failed_count, 0) AS failed_count,
                   batch.created_at,
                   task_summary.last_completed_at
            FROM analysis_batch batch
            LEFT JOIN (
                SELECT task.batch_id,
                       SUM(CASE WHEN task.status = 'WAITING' THEN 1 ELSE 0 END)
                           AS waiting_count,
                       SUM(CASE WHEN task.status = 'PROCESSING' THEN 1 ELSE 0 END)
                           AS processing_count,
                       SUM(CASE
                               WHEN task.status = 'FAILED'
                                AND task.dead_lettered_at IS NULL THEN 1
                               ELSE 0
                           END) AS retrying_count,
                       SUM(CASE WHEN task.status = 'SUCCESS' THEN 1 ELSE 0 END)
                           AS success_count,
                       SUM(CASE
                               WHEN task.status = 'FAILED'
                                AND task.dead_lettered_at IS NOT NULL THEN 1
                               ELSE 0
                           END) AS failed_count,
                       MAX(CASE
                               WHEN task.status = 'SUCCESS'
                                 OR task.dead_lettered_at IS NOT NULL
                               THEN task.completed_at
                           END) AS last_completed_at
                FROM analysis_task task
                WHERE task.batch_id = #{batchId}
                GROUP BY task.batch_id
            ) task_summary ON task_summary.batch_id = batch.id
            WHERE batch.id = #{batchId}
            """)
    AnalysisBatchProgressAggregate selectProgress(@Param("batchId") Long batchId);
}
