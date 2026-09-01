package de.codeministry.leadgen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The one check the skeleton owes: the context starts and Flyway actually ran.
 * Asserting on a table rather than on an empty context is the difference that
 * matters — a missing `spring-boot-flyway` module leaves the migrations on the
 * classpath, unexecuted, and an empty context test stays green through it.
 */
@SpringBootTest
@Testcontainers
class LeadGenerationApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatedTheBaselineSchema() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("source", "offer", "ingest_cursor", "flyway_schema_history");
    }
}
