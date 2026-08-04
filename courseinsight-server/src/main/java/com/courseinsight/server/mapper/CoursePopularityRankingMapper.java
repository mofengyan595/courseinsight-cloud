package com.courseinsight.server.mapper;

import com.courseinsight.server.dto.CoursePopularityAggregate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CoursePopularityRankingMapper {

    @Select("""
            SELECT course.id AS course_id,
                   COUNT(comment.id) AS comment_count
            FROM course
            INNER JOIN course_comment comment
                ON comment.course_id = course.id
               AND comment.status = 1
            WHERE course.status = 1
            GROUP BY course.id
            ORDER BY comment_count DESC, course.id DESC
            LIMIT #{limit}
            """)
    List<CoursePopularityAggregate> selectTopByCommentCount(
            @Param("limit") int limit);
}
