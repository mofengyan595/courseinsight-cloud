package com.courseinsight.server.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.dto.AnalysisBatchResultRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AnalysisBatchResultMapper {

    @Select("""
            SELECT task.id AS task_id,
                   task.comment_id,
                   comment.content,
                   comment.rating,
                   task.status AS task_status,
                   task.retry_count,
                   task.failure_reason,
                   task.dead_lettered_at,
                   task.completed_at AS task_completed_at,
                   result.id AS result_id,
                   result.language,
                   result.sentiment,
                   result.confidence,
                   result.sentiment_source,
                   result.sentiment_device,
                   result.topics_json,
                   result.topic_evidence_json,
                   result.keywords_json,
                   result.long_text_handled,
                   result.long_text_truncated,
                   result.advice_json,
                   result.risk_level,
                   result.advice_source,
                   result.created_at AS result_created_at
            FROM analysis_task task
            INNER JOIN course_comment comment ON comment.id = task.comment_id
            LEFT JOIN analysis_result result ON result.task_id = task.id
            WHERE task.batch_id = #{batchId}
            ORDER BY task.id ASC
            """)
    IPage<AnalysisBatchResultRow> selectPageByBatchId(
            Page<AnalysisBatchResultRow> page,
            @Param("batchId") Long batchId);
}
