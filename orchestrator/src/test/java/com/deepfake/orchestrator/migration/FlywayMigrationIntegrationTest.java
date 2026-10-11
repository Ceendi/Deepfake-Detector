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
 * Smoke test for the Flyway migrations: applies the full chain on a throwaway Postgres (matching the
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
    void perSourceDetailsColumnsAreJsonbAndLegacySingleColumnIsGone() throws Exception {
        List<String> types = query("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'analysis' AND column_name IN ('video_details', 'audio_details')");
        assertThat(types).hasSize(2).allMatch("jsonb"::equals);

        List<String> legacy = query("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'analysis' AND column_name = 'details'");
        assertThat(legacy).isEmpty();
    }

    @Test
    void timestampColumnsAreTimestamptz() throws Exception {
        List<String> types = query("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'analysis' AND column_name IN ('created_at', 'updated_at')");
        assertThat(types).hasSize(2).allMatch("timestamp with time zone"::equals);
    }

    @Test
    void artifactCleanupSurvivesParentDeletionAndHasDurableCursorAndLeases() throws Exception {
        assertThat(query("SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = 'artifact_cleanup' AND constraint_type = 'FOREIGN KEY'")).isEmpty();
        assertThat(query("SELECT column_name FROM information_schema.columns WHERE table_name = 'artifact_cleanup'"))
                .contains("object_key", "analysis_id", "next_attempt_at", "attempts", "lease_token", "lease_until");
        assertThat(query("SELECT id::text FROM artifact_cleanup_scan")).containsExactly("1");
        assertThat(query("SELECT indexname FROM pg_indexes WHERE tablename = 'analysis'"))
                .contains("idx_analysis_video_gradcam_keys", "idx_analysis_audio_gradcam_keys");
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
