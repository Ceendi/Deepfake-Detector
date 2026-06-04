package com.deepfake.orchestrator.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test for the Flyway migrations: applies V1 + V2 on a throwaway Postgres (matching the
 * compose image) and asserts the schema the rest of the sprint depends on — the covering and
 * partial indexes, plus the timestamptz columns. No Spring context: this exercises the SQL only,
 * so it does not need Redis/RabbitMQ/Eureka and stays decoupled from the app wiring.
 */
@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void coveringAndPartialIndexesExist() throws Exception {
        List<String> indexes =
                query("SELECT indexname FROM pg_indexes WHERE tablename = 'analysis'");
        assertThat(indexes).contains("idx_analysis_user_created", "idx_analysis_active");
    }

    @Test
    void timestampColumnsAreTimestamptz() throws Exception {
        List<String> types = query("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'analysis' AND column_name IN ('created_at', 'updated_at')");
        assertThat(types).hasSize(2).allMatch("timestamp with time zone"::equals);
    }

    private static List<String> query(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
