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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AnalysisBatchResultService {

    private static final int EXPORT_PAGE_SIZE = 100;
    private static final CSVFormat EXPORT_CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader(
                    "任务ID",
                    "评论ID",
                    "评论内容",
                    "评分",
                    "任务状态",
                    "重试次数",
                    "语言",
                    "情感",
                    "置信度",
                    "风险等级",
                    "关键词",
                    "主题",
                    "教学建议",
                    "失败原因",
                    "完成时间"
            )
            .get();

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

    @Transactional(readOnly = true)
    public String authorizeCsvExport(
            Long batchId,
            Long currentUserId,
            UserRole currentRole) {
        AnalysisBatch batch = requireBatch(batchId);
        accessService.assertCanManage(
                batch.getCourseId(),
                currentUserId,
                currentRole
        );
        return "analysis-batch-" + batch.getBatchNo() + ".csv";
    }

    public void writeCsv(Long batchId, OutputStream outputStream) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                outputStream,
                StandardCharsets.UTF_8
        ));
        writer.write('\uFEFF');

        CSVPrinter printer = new CSVPrinter(writer, EXPORT_CSV_FORMAT);
        long pageNumber = 1;
        while (true) {
            IPage<AnalysisBatchResultRow> resultPage =
                    resultMapper.selectPageByBatchId(
                            new Page<>(pageNumber, EXPORT_PAGE_SIZE, false),
                            batchId
                    );
            List<AnalysisBatchResultRow> rows = resultPage.getRecords();
            if (rows.isEmpty()) {
                break;
            }

            for (AnalysisBatchResultRow row : rows) {
                printer.printRecord(
                        row.getTaskId(),
                        row.getCommentId(),
                        safeForSpreadsheet(row.getContent()),
                        row.getRating(),
                        row.getTaskStatus(),
                        row.getRetryCount(),
                        row.getLanguage(),
                        row.getSentiment(),
                        row.getConfidence(),
                        row.getRiskLevel(),
                        row.getKeywordsJson(),
                        row.getTopicsJson(),
                        row.getAdviceJson(),
                        safeForSpreadsheet(row.getFailureReason()),
                        formatDateTime(row.getTaskCompletedAt())
                );
            }
            printer.flush();

            if (rows.size() < EXPORT_PAGE_SIZE) {
                break;
            }
            pageNumber++;
        }
        printer.flush();
    }

    private String safeForSpreadsheet(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-'
                || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    private AnalysisBatch requireBatch(Long batchId) {
        AnalysisBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("批量分析任务不存在");
        }
        return batch;
    }
}
