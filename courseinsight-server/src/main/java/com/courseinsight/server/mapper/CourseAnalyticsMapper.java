package com.courseinsight.server.mapper;

import com.courseinsight.server.dto.CourseAnalyticsAggregate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CourseAnalyticsMapper {

    @Select("""
            SELECT comment_summary.total_comments,
                   comment_summary.average_rating,
                   task_summary.total_tasks,
                   task_summary.waiting_tasks,
                   task_summary.processing_tasks,
                   task_summary.success_tasks,
                   task_summary.failed_tasks,
                   result_summary.analyzed_results,
                   result_summary.positive_results,
                   result_summary.neutral_results,
                   result_summary.negative_results,
                   result_summary.high_risk_results,
                   result_summary.middle_risk_results,
                   result_summary.low_risk_results
            FROM (
                SELECT COUNT(*) AS total_comments,
                       COALESCE(ROUND(AVG(rating), 2), 0.00) AS average_rating
                FROM course_comment
                WHERE course_id = #{courseId}
                  AND status = 1
            ) comment_summary
            CROSS JOIN (
                SELECT COUNT(*) AS total_tasks,
                       COALESCE(SUM(CASE WHEN task.status = 'WAITING' THEN 1 ELSE 0 END), 0)
                           AS waiting_tasks,
                       COALESCE(SUM(CASE WHEN task.status = 'PROCESSING' THEN 1 ELSE 0 END), 0)
                           AS processing_tasks,
                       COALESCE(SUM(CASE WHEN task.status = 'SUCCESS' THEN 1 ELSE 0 END), 0)
                           AS success_tasks,
                       COALESCE(SUM(CASE WHEN task.status = 'FAILED' THEN 1 ELSE 0 END), 0)
                           AS failed_tasks
                FROM analysis_task task
                INNER JOIN course_comment active_comment
                    ON active_comment.id = task.comment_id
                WHERE task.course_id = #{courseId}
                  AND active_comment.status = 1
            ) task_summary
            CROSS JOIN (
                SELECT COUNT(*) AS analyzed_results,
                       COALESCE(SUM(CASE WHEN analysis.sentiment = 'positive' THEN 1 ELSE 0 END), 0)
                           AS positive_results,
                       COALESCE(SUM(CASE WHEN analysis.sentiment = 'neutral' THEN 1 ELSE 0 END), 0)
                           AS neutral_results,
                       COALESCE(SUM(CASE WHEN analysis.sentiment = 'negative' THEN 1 ELSE 0 END), 0)
                           AS negative_results,
                       COALESCE(SUM(CASE WHEN analysis.risk_level = 'high' THEN 1 ELSE 0 END), 0)
                           AS high_risk_results,
                       COALESCE(SUM(CASE WHEN analysis.risk_level = 'middle' THEN 1 ELSE 0 END), 0)
                           AS middle_risk_results,
                       COALESCE(SUM(CASE WHEN analysis.risk_level = 'low' THEN 1 ELSE 0 END), 0)
                           AS low_risk_results
                FROM analysis_result analysis
                INNER JOIN course_comment active_comment
                    ON active_comment.id = analysis.comment_id
                WHERE analysis.course_id = #{courseId}
                  AND active_comment.status = 1
            ) result_summary
            """)
    CourseAnalyticsAggregate selectSummary(@Param("courseId") Long courseId);
}
