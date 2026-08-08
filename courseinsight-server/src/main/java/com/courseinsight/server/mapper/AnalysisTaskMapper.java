package com.courseinsight.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.courseinsight.server.entity.AnalysisTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    @Select("""
            SELECT id, task_no, comment_id, course_id, batch_id, status,
                   retry_count, failure_reason, started_at, completed_at,
                   dead_lettered_at, created_at, updated_at
            FROM analysis_task
            WHERE batch_id = #{batchId}
              AND status = 'FAILED'
              AND dead_lettered_at IS NOT NULL
            ORDER BY id ASC
            FOR UPDATE
            """)
    List<AnalysisTask> selectDeadLetteredByBatchIdForUpdate(
            @Param("batchId") Long batchId);
}
