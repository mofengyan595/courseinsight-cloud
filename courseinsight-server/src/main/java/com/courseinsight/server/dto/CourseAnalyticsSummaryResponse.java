package com.courseinsight.server.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CourseAnalyticsSummaryResponse(
        Long courseId,
        long totalComments,
        BigDecimal averageRating,
        TaskSummary tasks,
        SentimentSummary sentiments,
        RiskSummary risks
) {

    public static CourseAnalyticsSummaryResponse from(
            Long courseId,
            CourseAnalyticsAggregate aggregate) {
        long totalTasks = value(aggregate.getTotalTasks());
        long successTasks = value(aggregate.getSuccessTasks());
        long analyzedResults = value(aggregate.getAnalyzedResults());
        long positiveResults = value(aggregate.getPositiveResults());
        long neutralResults = value(aggregate.getNeutralResults());
        long negativeResults = value(aggregate.getNegativeResults());
        long highRiskResults = value(aggregate.getHighRiskResults());
        long middleRiskResults = value(aggregate.getMiddleRiskResults());
        long lowRiskResults = value(aggregate.getLowRiskResults());
        long unclassifiedRiskResults = Math.max(
                0,
                analyzedResults - highRiskResults - middleRiskResults - lowRiskResults
        );

        return new CourseAnalyticsSummaryResponse(
                courseId,
                value(aggregate.getTotalComments()),
                scale(aggregate.getAverageRating()),
                new TaskSummary(
                        totalTasks,
                        value(aggregate.getWaitingTasks()),
                        value(aggregate.getProcessingTasks()),
                        successTasks,
                        value(aggregate.getFailedTasks()),
                        percentage(successTasks, totalTasks)
                ),
                new SentimentSummary(
                        analyzedResults,
                        positiveResults,
                        neutralResults,
                        negativeResults,
                        percentage(positiveResults, analyzedResults),
                        percentage(neutralResults, analyzedResults),
                        percentage(negativeResults, analyzedResults)
                ),
                new RiskSummary(
                        highRiskResults,
                        middleRiskResults,
                        lowRiskResults,
                        unclassifiedRiskResults
                )
        );
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentage(long count, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    public record TaskSummary(
            long total,
            long waiting,
            long processing,
            long success,
            long failed,
            BigDecimal completionPercentage
    ) {
    }

    public record SentimentSummary(
            long total,
            long positive,
            long neutral,
            long negative,
            BigDecimal positivePercentage,
            BigDecimal neutralPercentage,
            BigDecimal negativePercentage
    ) {
    }

    public record RiskSummary(
            long high,
            long middle,
            long low,
            long unclassified
    ) {
    }
}
