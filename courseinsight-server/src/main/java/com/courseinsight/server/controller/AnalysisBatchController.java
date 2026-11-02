package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchProgressResponse;
import com.courseinsight.server.dto.AnalysisBatchResultItemResponse;
import com.courseinsight.server.dto.AnalysisBatchResultPageQuery;
import com.courseinsight.server.dto.AnalysisBatchRetryResponse;
import com.courseinsight.server.dto.AnalysisBatchPageQuery;
import com.courseinsight.server.dto.AnalysisBatchSummaryResponse;
import com.courseinsight.server.security.CurrentUser;
import com.courseinsight.server.service.AnalysisBatchRecoveryService;
import com.courseinsight.server.service.AnalysisBatchResultService;
import com.courseinsight.server.service.AnalysisBatchService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api")
public class AnalysisBatchController {

    private final AnalysisBatchService batchService;
    private final AnalysisBatchResultService resultService;
    private final AnalysisBatchRecoveryService recoveryService;

    public AnalysisBatchController(
            AnalysisBatchService batchService,
            AnalysisBatchResultService resultService,
            AnalysisBatchRecoveryService recoveryService) {
        this.batchService = batchService;
        this.resultService = resultService;
        this.recoveryService = recoveryService;
    }

    @PostMapping(
            value = "/courses/{courseId}/analysis-batches",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createAnalysisBatch", summary = "管理员上传 CSV 创建分析批次")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "课程不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "CSV 文件超过大小限制",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "上传过于频繁",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<AnalysisBatchCreateResponse> upload(
            @PathVariable @Positive(message = "ID 必须是正整数") Long courseId,
            Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(batchService.upload(
                courseId,
                currentUser.id(),
                currentUser.role(),
                file
        ));
    }

    @GetMapping("/analysis-batches")
    @Operation(operationId = "listAnalysisBatches", summary = "教师或管理员查询可访问的批次")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    public ApiResponse<PageResponse<AnalysisBatchSummaryResponse>> getBatches(
            Authentication authentication,
            @Valid @ParameterObject @ModelAttribute AnalysisBatchPageQuery query) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(batchService.page(query, currentUser.id(), currentUser.role()));
    }

    @GetMapping("/analysis-batches/{batchId}")
    @Operation(operationId = "getAnalysisBatch", summary = "教师或管理员查询批次进度")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "批次不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<AnalysisBatchProgressResponse> getProgress(
            @PathVariable @Positive(message = "ID 必须是正整数") Long batchId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(batchService.getProgress(
                batchId,
                currentUser.id(),
                currentUser.role()
        ));
    }

    @GetMapping("/analysis-batches/{batchId}/results")
    @Operation(operationId = "listAnalysisBatchResults", summary = "教师或管理员分页查询批次结果")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "批次不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<PageResponse<AnalysisBatchResultItemResponse>> getResults(
            @PathVariable @Positive(message = "ID 必须是正整数") Long batchId,
            Authentication authentication,
            @Valid @ParameterObject @ModelAttribute AnalysisBatchResultPageQuery query) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(resultService.page(
                batchId,
                currentUser.id(),
                currentUser.role(),
                query
        ));
    }

    @GetMapping(
            value = "/analysis-batches/{batchId}/export",
            produces = "text/csv"
    )
    @Operation(operationId = "exportAnalysisBatchResults", summary = "教师或管理员导出批次 CSV")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "批次不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "CSV 文件",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    public void exportResults(
            @PathVariable @Positive(message = "ID 必须是正整数") Long batchId,
            Authentication authentication,
            HttpServletResponse response) throws IOException {
        CurrentUser currentUser = CurrentUser.from(authentication);
        String filename = resultService.authorizeCsvExport(
                batchId,
                currentUser.id(),
                currentUser.role()
        );

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(filename)
                        .build()
                        .toString()
        );
        resultService.writeCsv(batchId, response.getOutputStream());
    }

    @PostMapping("/analysis-batches/{batchId}/retry-failed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "retryAnalysisBatch", summary = "管理员重试批次失败任务")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "批次不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<AnalysisBatchRetryResponse> retryFailed(
            @PathVariable @Positive(message = "ID 必须是正整数") Long batchId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(recoveryService.retryDeadLettered(
                batchId,
                currentUser.id(),
                currentUser.role()
        ));
    }
}
