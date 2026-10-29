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
 * {@code V26__review_round2.sql} contra un PostgreSQL de verdad: una base descartable migrada hasta V25 con ciclos
 * cerrados antes de la foto del cierre (sus columnas closed_* en NULL), y luego a V26.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewRound2MigrationTest {

    private String adminUrl;
    private String user;
    private String password;
    private String database;
    private String url;

    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final Map<String, UUID> ids = new HashMap<>();
    private int sequence;

    @BeforeAll
    void migrate() throws Exception {
        Map<String, String> env = settings();
        adminUrl = env.get("DB_URL");
        user = env.get("DB_USER");
        password = env.get("DB_PASSWORD");
        assumeTrue(adminUrl != null && adminUrl.startsWith("jdbc:postgresql:"), "Sin DB_URL de PostgreSQL");
        database = "taskflow_v26_test_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException ex) {
            assumeTrue(false, "PostgreSQL no disponible: " + ex.getMessage());
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;

        flyway("25").migrate();
        insertFixtures();
        flyway("26").migrate();
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
            // Luis cambió su contraseña después de V25
            sql.execute("INSERT INTO users (id, email, password_hash, full_name, credentials_changed_at) VALUES ('"
                    + luis + "', 'luis@test.com', 'x', 'Luis Muñoz', now() - interval '1 day')");
            sql.execute("INSERT INTO workspaces (id, name, slug, owner_id) VALUES ('" + workspace
                    + "', 'Equipo', 'equipo', '" + ana + "')");
            sql.execute("INSERT INTO projects (id, workspace_id, name, identifier, color) VALUES ('" + project + "', '"
                    + workspace + "', 'Sitio web', 'WEB', 'TEAL')");
            state(sql, "todo", "UNSTARTED", 0);
            state(sql, "doing", "STARTED", 1);
            state(sql, "done", "COMPLETED", 2);
            state(sql, "cancelled", "CANCELLED", 3);

            // Cerrado antes de V25: sin foto
            cycle(sql, "antiguo", "now() - interval '3 days'", 1, 2, null);
            // 1,25 días de En curso a Hecho
            issue(sql, "antiguo", "TASK", "done", "'2026-10-01T08:00:00Z'", "'2026-10-02T14:00:00Z'");
            // 4 días (un bug también es trabajo)
            issue(sql, "antiguo", "BUG", "done", "'2026-10-01T00:00:00Z'", "'2026-10-05T00:00:00Z'");
            // Completada sin pasar por En curso: cuenta como completada, no para el tiempo de ciclo
            issue(sql, "antiguo", "TASK", "done", "NULL", "'2026-10-05T00:00:00Z'");
            // Abierta: cuenta en el total
            issue(sql, "antiguo", "TASK", "doing", "'2026-10-01T00:00:00Z'", "NULL");
            // Cancelada e historia: no cuentan
            issue(sql, "antiguo", "TASK", "cancelled", "NULL", "NULL");
            issue(sql, "antiguo", "STORY", "done", "'2026-10-01T00:00:00Z'", "'2026-10-09T00:00:00Z'");

            // Cerrado antes de V25 y sin tareas: solo lo que se llevó
            cycle(sql, "vacio", "now() - interval '10 days'", 2, 1, null);
            // Cerrado después de V25: ya tiene su foto, no se toca
            cycle(sql, "conFoto", "now() - interval '1 day'", 0, 0, "5, 9, 1.5");
            issue(sql, "conFoto", "TASK", "doing", "NULL", "NULL");
            // Abierto: sin foto
            cycle(sql, "abierto", null, 0, 0, null);
            issue(sql, "abierto", "TASK", "done", "'2026-10-01T00:00:00Z'", "'2026-10-02T00:00:00Z'");
        }
    }

    private void state(Statement sql, String key, String group, int position) throws SQLException {
        UUID id = UUID.randomUUID();
        ids.put(key, id);
        sql.execute("INSERT INTO states (id, project_id, name, color, state_group, position) VALUES ('" + id
                + "', '" + project + "', '" + key + "', '#000000', '" + group + "', " + position + ")");
    }

    private void cycle(Statement sql, String key, String closedAt, int carriedToCycle, int carriedToBacklog,
                       String snapshot) throws SQLException {
        UUID id = UUID.randomUUID();
        ids.put(key, id);
        sql.execute("INSERT INTO cycles (id, project_id, name, closed_at, carried_to_cycle, carried_to_backlog, "
                + "closed_completed_count, closed_total_count, closed_cycle_time_days) VALUES ('" + id + "', '"
                + project + "', '" + key + "', " + (closedAt == null ? "NULL" : closedAt) + ", " + carriedToCycle
                + ", " + carriedToBacklog + ", " + (snapshot == null ? "NULL, NULL, NULL" : snapshot) + ")");
    }

    private void issue(Statement sql, String cycle, String type, String state, String startedAt, String completedAt)
            throws SQLException {
        UUID id = UUID.randomUUID();
        sequence++;
        sql.execute("INSERT INTO issues (id, project_id, sequence_id, title, state_id, sort_order, created_by_id, "
                + "issue_type, started_at, completed_at) VALUES ('" + id + "', '" + project + "', " + sequence
                + ", 'Tarea " + sequence + "', '" + ids.get(state) + "', " + sequence + ", '" + ana + "', '" + type
                + "', " + startedAt + ", " + completedAt + ")");
        sql.execute("INSERT INTO cycle_issues (cycle_id, issue_id) VALUES ('" + ids.get(cycle) + "', '" + id + "')");
    }

    private String value(String query) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private String snapshot(String cycle) throws SQLException {
        return value("SELECT concat_ws('|', closed_completed_count, closed_total_count, closed_cycle_time_days) "
                + "FROM cycles WHERE id = '" + ids.get(cycle) + "'");
    }

    @Test
    void lasCuentasQueYaCambiaronSuContrasenaEmpiezanEnLaVersion1() throws SQLException {
        assertThat(value("SELECT credentials_version FROM users WHERE id = '" + ana + "'")).isEqualTo("0");
        assertThat(value("SELECT credentials_version FROM users WHERE id = '" + luis + "'")).isEqualTo("1");
        assertThat(value("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'users' "
                + "AND column_name = 'credentials_version'")).isEqualTo("NO");
    }

    @Test
    void losCiclosCerradosAntesDeV25QuedanConSuFoto() throws SQLException {
        // Completadas: las 3 tareas y bugs en Hecho; total: las 4 no canceladas + 1 al ciclo siguiente + 2 al
        // backlog; tiempo de ciclo: mediana de 1,25 y 4 días = 2,625 → 2,6 (un decimal, como en Java)
        assertThat(snapshot("antiguo")).isEqualTo("3|7|2.6");
        assertThat(snapshot("vacio")).isEqualTo("0|3");
        assertThat(value("SELECT closed_cycle_time_days IS NULL FROM cycles WHERE id = '" + ids.get("vacio") + "'"))
                .isEqualTo("t");
    }

    @Test
    void losCiclosConFotoOAbiertosNoCambian() throws SQLException {
        assertThat(snapshot("conFoto")).isEqualTo("5|9|1.5");
        assertThat(value("SELECT count(*) FROM cycles WHERE id = '" + ids.get("abierto")
                + "' AND closed_completed_count IS NULL AND closed_total_count IS NULL "
                + "AND closed_cycle_time_days IS NULL")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM cycles WHERE closed_at IS NOT NULL AND closed_completed_count IS NULL"))
                .isEqualTo("0");
    }

    @Test
    void creaElIndiceDelCursorDeLaBandeja() throws SQLException {
        assertThat(value("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_notifications_recipient_cursor'"))
                .isEqualTo("1");
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
