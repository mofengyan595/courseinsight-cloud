package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchProgressResponse;
import com.courseinsight.server.dto.AnalysisBatchResultItemResponse;
import com.courseinsight.server.dto.AnalysisBatchResultPageQuery;
import com.courseinsight.server.dto.AnalysisBatchRetryResponse;
import com.courseinsight.server.security.CurrentUser;
import com.courseinsight.server.service.AnalysisBatchRecoveryService;
import com.courseinsight.server.service.AnalysisBatchResultService;
import com.courseinsight.server.service.AnalysisBatchService;
import jakarta.validation.Valid;
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
    public ApiResponse<AnalysisBatchCreateResponse> upload(
            @PathVariable Long courseId,
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

    @GetMapping("/analysis-batches/{batchId}")
    public ApiResponse<AnalysisBatchProgressResponse> getProgress(
            @PathVariable Long batchId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(batchService.getProgress(
                batchId,
                currentUser.id(),
                currentUser.role()
        ));
    }

    @GetMapping("/analysis-batches/{batchId}/results")
    public ApiResponse<PageResponse<AnalysisBatchResultItemResponse>> getResults(
            @PathVariable Long batchId,
            Authentication authentication,
            @Valid @ModelAttribute AnalysisBatchResultPageQuery query) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(resultService.page(
                batchId,
                currentUser.id(),
                currentUser.role(),
                query
        ));
    }

    @PostMapping("/analysis-batches/{batchId}/retry-failed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AnalysisBatchRetryResponse> retryFailed(
            @PathVariable Long batchId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(recoveryService.retryDeadLettered(
                batchId,
                currentUser.id(),
                currentUser.role()
        ));
    }
}
