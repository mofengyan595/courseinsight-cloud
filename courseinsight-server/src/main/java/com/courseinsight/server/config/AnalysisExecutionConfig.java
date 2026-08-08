package com.courseinsight.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AnalysisExecutionProperties.class)
public class AnalysisExecutionConfig {
}
