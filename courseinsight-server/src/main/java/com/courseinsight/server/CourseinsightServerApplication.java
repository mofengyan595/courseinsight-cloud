package com.courseinsight.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.courseinsight.server.mapper")
@SpringBootApplication
public class CourseinsightServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourseinsightServerApplication.class, args);
    }

}
