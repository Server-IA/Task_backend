package com.taskflow.api.integration;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.entity.User;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.security.JwtService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * El tiempo real con un servidor de verdad: las conexiones {@code GET /api/v1/events} (Server-Sent Events), quién
 * recibe el aviso de un cambio hecho por la API y quién no (otro workspace, otra persona), la pestaña que lo hizo
 * ({@code X-Client-Id}), las notificaciones y que sin token no se abre.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
        registry.add("app.jwt.secret", () -> "secreto-de-integracion-con-mas-de-32-caracteres");
        registry.add("app.mail.provider", () -> "resend");
        registry.add("app.mail.resend-api-key", () -> "");
        registry.add("app.frontend-url", () -> "http://localhost:5173");
        registry.add("app.maintenance.cron", () -> "-");
        registry.add("app.realtime.heartbeat", () -> "PT1H");
        // Como las demás pruebas con contexto propio: pocas conexiones (el PostgreSQL de pruebas tiene un tope)
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @BeforeAll
    static void requirePostgres() {
        assumeTrue(TestDatabase.available(), "PostgreSQL no disponible (DB_URL)");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<CompletableFuture<?>> streams = new ArrayList<>();

    private User pm;
    private User member;
    private User stranger;
    private String slug;
    private UUID projectId;

    @BeforeEach
    void workspace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-rt-" + suffix + "@test.com", "Paula PM");
        member = user("luis-rt-" + suffix + "@test.com", "Luis Miembro");
        stranger = user("otra-rt-" + suffix + "@test.com", "Otra Persona");
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Tiempo real " + suffix, "rt-" + suffix)).slug();
        workspaceService.create(stranger.getId(), new CreateWorkspaceRequest("Ajeno " + suffix, "rt-ajeno-" + suffix));
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) SELECT gen_random_uuid(), w.id, '"
                + member.getId() + "', 'MEMBER' FROM workspaces w WHERE slug = '" + slug + "'");
        projectId = projectService.create(slug, pm.getId(), new CreateProjectRequest("Web", "RT" + suffix.substring(0, 2)
                .toUpperCase().replaceAll("[0-9]", "X"), null, null)).id();
        projectService.addMember(slug, projectId, new AddProjectMemberRequest(member.getId()));
    }

    @AfterEach
    void close() {
        streams.forEach(stream -> stream.cancel(true));
    }

    @Test
    void unCambioLlegaAQuienPuedeVerloConLaPestanaQueLoHizo() throws Exception {
        BlockingQueue<String> luis = connect(member);
        BlockingQueue<String> ajena = connect(stranger);
        assertThat(next(luis, "event:ready")).isNotNull();
        assertThat(next(ajena, "event:ready")).isNotNull();

        HttpResponse<String> created = send(pm, "POST", "/api/v1/workspaces/" + slug + "/projects/" + projectId + "/issues",
                "{\"title\":\"Llega en vivo\"}", "pestana-de-paula");
        assertThat(created.statusCode()).isEqualTo(201);

        String change = next(luis, "event:change");
        assertThat(change).isNotNull();
        String data = next(luis, "data:");
        assertThat(data).contains("\"workspace\":\"" + slug + "\"").contains("\"projectId\":\"" + projectId + "\"")
                .contains("\"origin\":\"pestana-de-paula\"");
        // Quien no es del workspace no se entera de nada
        assertThat(next(ajena, "event:change", 700)).isNull();
    }

    @Test
    void unaPeticionQueFallaNoAvisa() throws Exception {
        BlockingQueue<String> luis = connect(member);
        assertThat(next(luis, "event:ready")).isNotNull();

        HttpResponse<String> invalid = send(pm, "POST", "/api/v1/workspaces/" + slug + "/projects/" + projectId + "/issues",
                "{\"title\":\"\"}", null);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(next(luis, "event:change", 700)).isNull();
    }

    @Test
    void marcarLeidasActualizaLasOtrasPestanasDeEsaPersona() throws Exception {
        BlockingQueue<String> otraPestana = connect(member);
        BlockingQueue<String> paula = connect(pm);
        assertThat(next(otraPestana, "event:ready")).isNotNull();
        assertThat(next(paula, "event:ready")).isNotNull();

        assertThat(send(member, "POST", "/api/v1/notifications/read-all", "", "pestana-1").statusCode()).isLessThan(300);

        assertThat(next(otraPestana, "event:notifications")).isNotNull();
        assertThat(next(otraPestana, "data:")).contains("\"origin\":\"pestana-1\"");
        assertThat(next(paula, "event:notifications", 700)).isNull();
    }

    @Test
    void sinTokenNoSeAbre() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/v1/events"))
                .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- ayudas

    /** Abre la conexión de una pestaña y va dejando sus líneas en una cola. */
    private BlockingQueue<String> connect(User user) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/events"))
                .header("Authorization", "Bearer " + token(user))
                .header("Accept", "text/event-stream")
                .GET().build();
        streams.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    lines.add("status:" + response.statusCode());
                    response.body().forEach(line -> lines.add(line.replace(": ", ":")));
                }));
        return lines;
    }

    private String next(BlockingQueue<String> lines, String prefix) throws InterruptedException {
        return next(lines, prefix, 10_000);
    }

    /** La siguiente línea que empieza así (las demás se saltan), o {@code null} si no llega a tiempo. */
    private static String next(BlockingQueue<String> lines, String prefix, long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            String line = lines.poll(until - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    private HttpResponse<String> send(User user, String method, String path, String body, String clientId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token(user))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (clientId != null) {
            request.header("X-Client-Id", clientId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(User user) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getCredentialsVersion());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static void sql(String statement) {
        try (java.sql.Connection connection = TestDatabase.connect();
             java.sql.PreparedStatement query = connection.prepareStatement(statement)) {
            query.execute();
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private User user(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode("Clave1234"));
        user.setEmailVerified(true);
        return userRepository.saveAndFlush(user);
    }
}
