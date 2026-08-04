package com.courseinsight.server.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CoursePopularityAggregate {

    private Long courseId;
    private Long commentCount;
}
