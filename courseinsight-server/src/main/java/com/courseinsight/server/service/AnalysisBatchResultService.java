package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AnalysisBatchResultItemResponse;
import com.courseinsight.server.dto.AnalysisBatchResultPageQuery;
import com.courseinsight.server.dto.AnalysisBatchResultRow;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisBatchResultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AnalysisBatchResultService {

    private final AnalysisBatchMapper batchMapper;
    private final AnalysisBatchResultMapper resultMapper;
    private final CourseManagementAccessService accessService;
    private final ObjectMapper objectMapper;

    public AnalysisBatchResultService(
            AnalysisBatchMapper batchMapper,
            AnalysisBatchResultMapper resultMapper,
            CourseManagementAccessService accessService,
            ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.resultMapper = resultMapper;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnalysisBatchResultItemResponse> page(
            Long batchId,
            Long currentUserId,
            UserRole currentRole,
            AnalysisBatchResultPageQuery query) {
        AnalysisBatch batch = requireBatch(batchId);
        accessService.assertCanManage(
                batch.getCourseId(),
                currentUserId,
                currentRole
        );

        IPage<AnalysisBatchResultRow> result = resultMapper.selectPageByBatchId(
                new Page<>(query.page(), query.size()),
                batchId
        );
        List<AnalysisBatchResultItemResponse> items = result.getRecords()
                .stream()
                .map(row -> AnalysisBatchResultItemResponse.from(row, objectMapper))
                .toList();

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                items
        );
    }

    private AnalysisBatch requireBatch(Long batchId) {
        AnalysisBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("批量分析任务不存在");
        }
        return batch;
    }
}
