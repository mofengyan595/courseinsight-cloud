package com.courseinsight.server.testsupport;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

final class MySqlTestContainers {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4")
    )
            .withDatabaseName("courseinsight_test")
            .withUsername("courseinsight_test")
            .withPassword("courseinsight_test");

    private MySqlTestContainers() {
    }
}
