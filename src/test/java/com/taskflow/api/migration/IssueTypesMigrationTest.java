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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code V24__issue_types.sql} contra un PostgreSQL de verdad: una base nueva y descartable en el mismo servidor de
 * {@code DB_URL} (variables de entorno o el archivo {@code .env}), migrada hasta V23, con datos de ejemplo, y luego a
 * V24. Si no hay un PostgreSQL disponible, la prueba se omite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTypesMigrationTest {

    private String adminUrl;
    private String user;
    private String password;
    private String database;
    private String url;

    private final UUID ana = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID backlog = UUID.randomUUID();
    private final UUID inProgress = UUID.randomUUID();
    private final UUID done = UUID.randomUUID();
    private final Map<String, UUID> issues = new HashMap<>();

    @BeforeAll
    void migrate() throws Exception {
        Map<String, String> env = settings();
        adminUrl = env.get("DB_URL");
        user = env.get("DB_USER");
        password = env.get("DB_PASSWORD");
        assumeTrue(adminUrl != null && adminUrl.startsWith("jdbc:postgresql:"), "Sin DB_URL de PostgreSQL");
        database = "taskflow_v24_test_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException ex) {
            assumeTrue(false, "PostgreSQL no disponible: " + ex.getMessage());
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;

        flyway("23").migrate();
        insertFixtures();
        flyway("24").migrate();
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
            state(sql, backlog, "Backlog", "BACKLOG", 0);
            state(sql, inProgress, "In Progress", "STARTED", 1);
            state(sql, done, "Done", "COMPLETED", 2);

            issue(sql, "bug", 1, "[Bug] El login falla con mayúsculas", null, backlog, null);
            issue(sql, "bugMinusculas", 2, "[bug] La portada se corta", null, backlog, null);
            issue(sql, "historiaCerrada", 3, "Pantalla de perfil",
                    "> **Como** persona registrada\n> **quiero** editar mi nombre\n> **para** tenerlo al día", done,
                    null);
            issue(sql, "historiaConIntro", 4, "Registro", "Contexto.\n\n> **Como** visitante\n> **quiero** registrarme",
                    backlog, null);
            issue(sql, "tarea", 5, "Optimizar imágenes", "Como dice el título, sin cita", backlog, null);
            issue(sql, "bugConCita", 6, "[Bug] El nombre no se actualiza", "> **Como** usuario", backlog, null);
            issue(sql, "historiaLista", 7, "Iniciar sesión", "> **Como** usuario", inProgress, null);
            issue(sql, "hijaTerminada", 8, "Endpoint de login", null, done, issues.get("historiaLista"));
            issue(sql, "historiaEnCurso", 9, "Recuperar contraseña", "> **Como** usuario", inProgress, null);
            issue(sql, "hijaAbierta", 10, "Formulario", null, inProgress, issues.get("historiaEnCurso"));

            sql.execute("UPDATE issues SET completed_at = '2026-11-12T10:00:00Z' WHERE id = '"
                    + issues.get("historiaCerrada") + "'");
            sql.execute("INSERT INTO issue_activities (id, issue_id, actor_id, field, old_value, new_value) VALUES ('"
                    + UUID.randomUUID() + "', '" + issues.get("historiaCerrada") + "', '" + ana
                    + "', 'STATE', 'In Progress', 'Done')");

            template(sql, "Reporte de bug", "[Bug] ", "## Pasos", 0);
            template(sql, "Historia de usuario", null, "> **Como**\n> **quiero**\n> **para**", 1);
            template(sql, "Tarea técnica", null, "## Objetivo", 2);
            template(sql, "Bug de pagos", "[Bug] ", null, 3);
            template(sql, "Épica", null, "Intro\n> **Como** cliente", 4);
        }
    }

    private void state(Statement sql, UUID id, String name, String group, int position) throws SQLException {
        sql.execute("INSERT INTO states (id, project_id, name, color, state_group, position) VALUES ('" + id + "', '"
                + project + "', '" + name + "', '#000000', '" + group + "', " + position + ")");
    }

    private void issue(Statement sql, String key, int number, String title, String description, UUID state,
                       UUID parent) throws SQLException {
        UUID id = UUID.randomUUID();
        issues.put(key, id);
        sql.execute("INSERT INTO issues (id, project_id, sequence_id, title, description, state_id, parent_id, "
                + "sort_order, created_by_id) VALUES ('" + id + "', '" + project + "', " + number + ", "
                + literal(title) + ", " + literal(description) + ", '" + state + "', "
                + (parent == null ? "NULL" : "'" + parent + "'") + ", " + number + ", '" + ana + "')");
    }

    private void template(Statement sql, String name, String prefix, String description, int position)
            throws SQLException {
        sql.execute("INSERT INTO issue_templates (id, project_id, name, title_prefix, description, position) VALUES ('"
                + UUID.randomUUID() + "', '" + project + "', " + literal(name) + ", " + literal(prefix) + ", "
                + literal(description) + ", " + position + ")");
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "E'" + value.replace("'", "''").replace("\n", "\\n") + "'";
    }

    private String value(String query) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private String type(String key) throws SQLException {
        return value("SELECT issue_type FROM issues WHERE id = '" + issues.get(key) + "'");
    }

    @Test
    void losTitulosConElPrefijoBugSonBugsAunqueTenganLaCita() throws SQLException {
        assertThat(type("bug")).isEqualTo("BUG");
        assertThat(type("bugMinusculas")).isEqualTo("BUG");
        assertThat(type("bugConCita")).isEqualTo("BUG");
    }

    @Test
    void laCitaDeLaHistoriaDeUsuarioLasVuelveHistorias() throws SQLException {
        assertThat(type("historiaCerrada")).isEqualTo("STORY");
        assertThat(type("historiaConIntro")).isEqualTo("STORY");
        assertThat(type("historiaLista")).isEqualTo("STORY");
    }

    @Test
    void elRestoSonTareas() throws SQLException {
        assertThat(type("tarea")).isEqualTo("TASK");
        assertThat(type("hijaTerminada")).isEqualTo("TASK");
        assertThat(value("SELECT count(*) FROM issues WHERE issue_type IS NULL")).isEqualTo("0");
    }

    @Test
    void lasHistoriasCerradasGuardanCuandoYQuienLasCerro() throws SQLException {
        UUID closed = issues.get("historiaCerrada");
        assertThat(value("SELECT closed_at = completed_at FROM issues WHERE id = '" + closed + "'")).isEqualTo("t");
        assertThat(value("SELECT closed_by_id FROM issues WHERE id = '" + closed + "'")).isEqualTo(ana.toString());
        assertThat(value("SELECT closed_at FROM issues WHERE id = '" + issues.get("historiaLista") + "'")).isNull();
    }

    @Test
    void lasHistoriasYaListasParaRevisarCuentanComoAvisadas() throws SQLException {
        assertThat(value("SELECT review_notified FROM issues WHERE id = '" + issues.get("historiaLista") + "'"))
                .isEqualTo("t");
        assertThat(value("SELECT review_notified FROM issues WHERE id = '" + issues.get("historiaEnCurso") + "'"))
                .isEqualTo("f");
    }

    @Test
    void lasPlantillasFijanElTipo() throws SQLException {
        String query = "SELECT issue_type FROM issue_templates WHERE name = '%s'";
        assertThat(value(String.format(Locale.ROOT, query, "Reporte de bug"))).isEqualTo("BUG");
        assertThat(value(String.format(Locale.ROOT, query, "Historia de usuario"))).isEqualTo("STORY");
        assertThat(value(String.format(Locale.ROOT, query, "Tarea técnica"))).isEqualTo("TASK");
        assertThat(value(String.format(Locale.ROOT, query, "Bug de pagos"))).isEqualTo("BUG");
        assertThat(value(String.format(Locale.ROOT, query, "Épica"))).isEqualTo("STORY");
    }

    @Test
    void elTipoSoloAceptaHistoriaTareaOBugYElAvisoNuevoExiste() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement sql = connection.createStatement()) {
            assertThatThrownBy(() -> sql.execute("UPDATE issues SET issue_type = 'EPIC' WHERE id = '"
                    + issues.get("tarea") + "'")).isInstanceOf(SQLException.class);
            sql.execute("INSERT INTO notifications (id, recipient_id, type, message, issue_id) VALUES ('"
                    + UUID.randomUUID() + "', '" + ana + "', 'STORY_READY', 'WEB-7 está lista para revisar', '"
                    + issues.get("historiaLista") + "')");
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
