package com.courseinsight.server.exception;

public class CourseNotEmptyException extends RuntimeException {

    public CourseNotEmptyException() {
        super("课程有关联评价或分析数据，不能删除");
    }
}
