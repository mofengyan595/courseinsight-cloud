package com.courseinsight.server.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CourseAnalyticsAggregate {

    private Long totalComments;
    private BigDecimal averageRating;
    private Long totalTasks;
    private Long waitingTasks;
    private Long processingTasks;
    private Long successTasks;
    private Long failedTasks;
    private Long analyzedResults;
    private Long positiveResults;
    private Long neutralResults;
    private Long negativeResults;
    private Long highRiskResults;
    private Long middleRiskResults;
    private Long lowRiskResults;
}
