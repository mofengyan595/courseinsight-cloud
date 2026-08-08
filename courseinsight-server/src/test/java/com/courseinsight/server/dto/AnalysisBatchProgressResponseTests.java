package com.courseinsight.server.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisBatchProgressResponseTests {

    @Test
    void shouldKeepRetryableFailuresInProcessingStatus() {
        AnalysisBatchProgressAggregate aggregate = aggregate();
        aggregate.setTotalCount(4);
        aggregate.setWaitingCount(1L);
        aggregate.setRetryingCount(1L);
        aggregate.setSuccessCount(2L);

        AnalysisBatchProgressResponse response =
                AnalysisBatchProgressResponse.from(aggregate);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.retryingCount()).isEqualTo(1);
        assertThat(response.completionPercentage()).isEqualByComparingTo("50.00");
        assertThat(response.completedAt()).isNull();
    }

    @Test
    void shouldReturnPartialFailureWhenEveryTaskIsTerminal() {
        AnalysisBatchProgressAggregate aggregate = aggregate();
        aggregate.setTotalCount(4);
        aggregate.setSuccessCount(3L);
        aggregate.setFailedCount(1L);
        aggregate.setLastCompletedAt(LocalDateTime.of(2026, 8, 8, 12, 0));

        AnalysisBatchProgressResponse response =
                AnalysisBatchProgressResponse.from(aggregate);

        assertThat(response.status()).isEqualTo("PARTIAL_FAILED");
        assertThat(response.completionPercentage())
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(response.completedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 8, 12, 0));
    }

    private AnalysisBatchProgressAggregate aggregate() {
        AnalysisBatchProgressAggregate aggregate = new AnalysisBatchProgressAggregate();
        aggregate.setBatchId(1L);
        aggregate.setBatchNo("batch-1");
        aggregate.setCourseId(14L);
        aggregate.setCreatedBy(11L);
        aggregate.setOriginalFilename("comments.csv");
        aggregate.setWaitingCount(0L);
        aggregate.setProcessingCount(0L);
        aggregate.setRetryingCount(0L);
        aggregate.setSuccessCount(0L);
        aggregate.setFailedCount(0L);
        aggregate.setCreatedAt(LocalDateTime.of(2026, 8, 8, 10, 0));
        return aggregate;
    }
}
