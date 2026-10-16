package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchCsvData;
import com.courseinsight.server.dto.AnalysisBatchProgressAggregate;
import com.courseinsight.server.dto.AnalysisBatchProgressResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AnalysisBatchPageQuery;
import com.courseinsight.server.dto.AnalysisBatchSummaryResponse;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisBatchProgressMapper;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import com.courseinsight.server.security.WriteAccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AnalysisBatchService {

    private final CourseManagementAccessService accessService;
    private final AnalysisBatchCsvParser csvParser;
    private final AnalysisBatchCreationService creationService;
    private final AnalysisBatchMapper batchMapper;
    private final AnalysisBatchProgressMapper progressMapper;
    private final RedisRateLimiter rateLimiter;

    public AnalysisBatchService(
            CourseManagementAccessService accessService,
            AnalysisBatchCsvParser csvParser,
            AnalysisBatchCreationService creationService,
            AnalysisBatchMapper batchMapper,
            AnalysisBatchProgressMapper progressMapper,
            RedisRateLimiter rateLimiter) {
        this.accessService = accessService;
        this.csvParser = csvParser;
        this.creationService = creationService;
        this.batchMapper = batchMapper;
        this.progressMapper = progressMapper;
        this.rateLimiter = rateLimiter;
    }

    public AnalysisBatchCreateResponse upload(
            Long courseId,
            Long currentUserId,
            UserRole currentRole,
            MultipartFile file) {
        WriteAccessPolicy.requireAdmin(currentRole);
        rateLimiter.check(RateLimitPolicy.BATCH_ANALYSIS_UPLOAD, currentUserId);
        accessService.assertCanManage(courseId, currentUserId, currentRole);
        AnalysisBatchCsvData csvData = csvParser.parse(file);
        return creationService.create(
                courseId,
                currentUserId,
                csvData.originalFilename(),
                csvData.rows()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AnalysisBatchSummaryResponse> page(
            AnalysisBatchPageQuery query,
            Long currentUserId,
            UserRole currentRole) {
        if (currentRole != UserRole.TEACHER && currentRole != UserRole.ADMIN) {
            throw new CourseAccessDeniedException("仅教师或管理员可以查看批量分析任务");
        }
        var result = batchMapper.selectManageablePage(
                new Page<>(query.page(), query.size()),
                currentRole == UserRole.ADMIN ? null : currentUserId
        );
        return new PageResponse<>(result.getCurrent(), result.getSize(),
                result.getTotal(), result.getPages(), result.getRecords());
    }

    @Transactional(readOnly = true)
    public AnalysisBatchProgressResponse getProgress(
            Long batchId,
            Long currentUserId,
            UserRole currentRole) {
        AnalysisBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("批量分析任务不存在");
        }
        accessService.assertCanAnalyze(batch.getCourseId(), currentUserId, currentRole);

        AnalysisBatchProgressAggregate progress = progressMapper.selectProgress(batchId);
        if (progress == null) {
            throw new ResourceNotFoundException("批量分析任务不存在");
        }
        return AnalysisBatchProgressResponse.from(progress);
    }
}
