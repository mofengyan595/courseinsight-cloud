package com.courseinsight.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.courseinsight.server.entity.AnalysisBatch;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.dto.AnalysisBatchSummaryResponse;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

public interface AnalysisBatchMapper extends BaseMapper<AnalysisBatch> {
    @Select("""
            <script>
            SELECT batch.id AS batch_id, batch.batch_no, batch.course_id,
                   course.name AS course_name, batch.original_filename,
                   batch.total_count, batch.created_at
            FROM analysis_batch batch
            INNER JOIN course ON course.id = batch.course_id
            <if test="ownerUserId != null">
            WHERE course.owner_user_id = #{ownerUserId}
            </if>
            ORDER BY batch.created_at DESC, batch.id DESC
            </script>
            """)
    IPage<AnalysisBatchSummaryResponse> selectManageablePage(
            Page<AnalysisBatchSummaryResponse> page,
            @Param("ownerUserId") Long ownerUserId);
}
