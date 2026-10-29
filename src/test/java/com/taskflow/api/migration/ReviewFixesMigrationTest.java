package com.taskflow.api.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code V25__review_fixes.sql} contra un PostgreSQL de verdad: una base descartable migrada hasta V24 con datos que
 * V24 dejó mal (historias con padre, también una historia hija de otra) y luego a V25.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewFixesMigrationTest {

    private String adminUrl;
    private String user;
    private String password;
    private String database;
    private String url;

    private final UUID ana = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID todo = UUID.randomUUID();
    private final Map<String, UUID> issues = new HashMap<>();

    @BeforeAll
    void migrate() throws Exception {
        Map<String, String> env = settings();
        adminUrl = env.get("DB_URL");
        user = env.get("DB_USER");
        password = env.get("DB_PASSWORD");
        assumeTrue(adminUrl != null && adminUrl.startsWith("jdbc:postgresql:"), "Sin DB_URL de PostgreSQL");
        database = "taskflow_v25_test_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException ex) {
            assumeTrue(false, "PostgreSQL no disponible: " + ex.getMessage());
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;

        flyway("24").migrate();
        insertFixtures();
        flyway("25").migrate();
    }

    @AfterAll
    void dropDatabase() throws SQLException {
        if (database == null || url == null) {
            return;
        }
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private void insertFixtures() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement sql = connection.createStatement()) {
            UUID workspace = UUID.randomUUID();
            sql.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + ana
                    + "', 'ana@test.com', 'x', 'Ana Navarro')");
            sql.execute("INSERT INTO workspaces (id, name, slug, owner_id) VALUES ('" + workspace
                    + "', 'Equipo', 'equipo', '" + ana + "')");
            sql.execute("INSERT INTO projects (id, workspace_id, name, identifier, color) VALUES ('" + project + "', '"
                    + workspace + "', 'Sitio web', 'WEB', 'TEAL')");
            sql.execute("INSERT INTO states (id, project_id, name, color, state_group, position) VALUES ('" + todo
                    + "', '" + project + "', 'Todo', '#000000', 'UNSTARTED', 0)");

            issue(sql, "tareaMadre", 1, "TASK", null);
            issue(sql, "historiaHija", 2, "STORY", issues.get("tareaMadre"));
            issue(sql, "historiaDeHistoria", 3, "STORY", issues.get("historiaHija"));
            issue(sql, "tareaDeHistoria", 4, "TASK", issues.get("historiaHija"));
            issue(sql, "historiaSuelta", 5, "STORY", null);
        }
    }

    private void issue(Statement sql, String key, int number, String type, UUID parent) throws SQLException {
        UUID id = UUID.randomUUID();
        issues.put(key, id);
        sql.execute("INSERT INTO issues (id, project_id, sequence_id, title, state_id, parent_id, sort_order, "
                + "created_by_id, issue_type) VALUES ('" + id + "', '" + project + "', " + number + ", '" + key
                + "', '" + todo + "', " + (parent == null ? "NULL" : "'" + parent + "'") + ", " + number + ", '" + ana
                + "', '" + type + "')");
    }

    private String value(String query) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private String parentOf(String key) throws SQLException {
        return value("SELECT parent_id FROM issues WHERE id = '" + issues.get(key) + "'");
    }

    @Test
    void lasHistoriasConPadreQuedanSueltas() throws SQLException {
        assertThat(parentOf("historiaHija")).isNull();
        assertThat(parentOf("historiaDeHistoria")).isNull();
        assertThat(value("SELECT count(*) FROM issues WHERE issue_type = 'STORY' AND parent_id IS NOT NULL"))
                .isEqualTo("0");
    }

    @Test
    void lasTareasConservanSuHistoria() throws SQLException {
        assertThat(parentOf("tareaDeHistoria")).isEqualTo(issues.get("historiaHija").toString());
        assertThat(value("SELECT issue_type FROM issues WHERE id = '" + issues.get("historiaHija") + "'"))
                .isEqualTo("STORY");
    }

    @Test
    void agregaLasColumnasNuevasVacias() throws SQLException {
        assertThat(value("SELECT credentials_changed_at FROM users WHERE id = '" + ana + "'")).isNull();
        assertThat(value("SELECT count(*) FROM information_schema.columns WHERE table_name = 'cycles' "
                + "AND column_name IN ('closed_completed_count', 'closed_total_count', 'closed_cycle_time_days')"))
                .isEqualTo("3");
    }

    @Test
    void creaLosIndices() throws SQLException {
        for (String index : new String[] {"idx_notifications_issue", "idx_notifications_actor",
                "idx_issues_state_sort", "idx_refresh_tokens_expires", "idx_verification_tokens_expires",
                "idx_notifications_read_created"}) {
            assertThat(value("SELECT count(*) FROM pg_indexes WHERE indexname = '" + index + "'"))
                    .as(index).isEqualTo("1");
        }
    }

    /** {@code DB_URL}, {@code DB_USER} y {@code DB_PASSWORD} del entorno o, si no están, del archivo {@code .env}. */
    private static Map<String, String> settings() throws IOException {
        Map<String, String> values = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals > 0 && !line.startsWith("#")) {
                    values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
                }
            }
        }
        for (String key : new String[] {"DB_URL", "DB_USER", "DB_PASSWORD"}) {
            String fromEnv = System.getenv(key);
            if (fromEnv != null && !fromEnv.isBlank()) {
                values.put(key, fromEnv);
            }
        }
        return values;
    }
}
