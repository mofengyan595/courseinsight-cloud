package com.courseinsight.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.courseinsight.server.entity.Course;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CourseMapper extends BaseMapper<Course> {

    @Select("SELECT * FROM course WHERE id = #{id} FOR SHARE")
    Course selectForAssociation(@Param("id") Long id);

    @Select("SELECT * FROM course WHERE id = #{id} FOR UPDATE")
    Course selectForDeletion(@Param("id") Long id);

    @Delete("""
            DELETE FROM course WHERE id = #{id}
              AND NOT EXISTS (SELECT 1 FROM course_comment WHERE course_id = #{id})
              AND NOT EXISTS (SELECT 1 FROM analysis_batch WHERE course_id = #{id})
              AND NOT EXISTS (SELECT 1 FROM analysis_task WHERE course_id = #{id})
              AND NOT EXISTS (SELECT 1 FROM analysis_result WHERE course_id = #{id})
            """)
    int deleteIfEmpty(@Param("id") Long id);
}
