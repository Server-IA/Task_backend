package com.taskflow.api.integration;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.CommentRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateUploadRequest;
import com.taskflow.api.dto.request.CreateUploadRequest.UploadPurpose;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.AvatarUploadRequest;
import com.taskflow.api.dto.response.ActivityResponse;
import com.taskflow.api.dto.response.FileResponse;
import com.taskflow.api.dto.response.FileUrlResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.dto.response.StorageReportResponse;
import com.taskflow.api.dto.response.UploadTicketResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.FileStatus;
import com.taskflow.api.entity.enums.NotificationType;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.CommentService;
import com.taskflow.api.service.FileService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.WorkspaceService;
import com.taskflow.api.storage.ObjectStorage;
import com.taskflow.api.storage.ObjectStorage.ObjectInfo;
import com.taskflow.api.storage.ObjectStorage.PresignedRequest;
import com.taskflow.api.storage.StorageCleanup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Archivos contra un PostgreSQL de verdad ({@link TestDatabase}) con R2 simulado: el trigger que encola los objetos al
 * borrar (también en cascada con la tarea o el workspace), la cola de borrado, los permisos de las URLs con las
 * consultas reales, la cuota, la limpieza de subidas abandonadas y el aviso del 90 %.
 */
@SpringBootTest
class StorageIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

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
        registry.add("app.storage.cleanup-cron", () -> "-");
        registry.add("app.storage.drain-initial-delay", () -> "PT10H");
    }

    @BeforeAll
    static void requirePostgres() {
        assumeTrue(TestDatabase.available(), "PostgreSQL no disponible (DB_URL)");
    }

    @MockitoBean
    private ObjectStorage storage;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private IssueService issueService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private FileService fileService;
    @Autowired
    private StorageCleanup storageCleanup;

    private String suffix;
    private User pm;
    private User luis;
    private String slug;
    private UUID web;
    private UUID ops;

    @BeforeEach
    void setUp() {
        when(storage.enabled()).thenReturn(true);
        when(storage.presignUpload(anyString(), anyString(), anyLong(), any())).thenAnswer(call -> new PresignedRequest(
                "https://r2.test/" + call.getArgument(0), Map.of("Content-Type", call.getArgument(1)),
                Instant.now().plusSeconds(900)));
        when(storage.presignDownload(anyString(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(call -> new PresignedRequest("https://r2.test/" + call.getArgument(0), Map.of(),
                        Instant.now().plusSeconds(3600)));
        // Lo que «llega» a R2 es lo que se firmó
        when(storage.head(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            String size = value("SELECT size_bytes FROM stored_files WHERE object_key = '" + key + "'");
            String type = value("SELECT content_type FROM stored_files WHERE object_key = '" + key + "'");
            return size == null ? java.util.Optional.empty()
                    : java.util.Optional.of(new ObjectInfo(Long.parseLong(size), type));
        });
        when(storage.readPrefix(anyString(), anyInt())).thenAnswer(call -> {
            String key = call.getArgument(0);
            return key.endsWith(".pdf") ? PDF : PNG;
        });

        suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-" + suffix + "@test.com", "Paula PM");
        luis = user("luis-" + suffix + "@test.com", "Luis Miembro");
        slug = workspaceService.create(pm.getId(), new CreateWorkspaceRequest("Equipo " + suffix, "eq-" + suffix))
                .slug();
        workspaceMember(luis, slug);
        web = projectService.create(slug, pm.getId(), new CreateProjectRequest("Web", code("W"), null, null)).id();
        ops = projectService.create(slug, pm.getId(), new CreateProjectRequest("Ops", code("O"), null, null)).id();
        // Luis solo está en Web
        projectService.addMember(slug, web, new AddProjectMemberRequest(luis.getId()));
    }

    private String code(String letter) {
        return letter + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X");
    }

    private User user(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode("Clave1234"));
        user.setEmailVerified(true);
        return userRepository.saveAndFlush(user);
    }

    private void workspaceMember(User user, String workspaceSlug) {
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) SELECT gen_random_uuid(), w.id, '"
                + user.getId() + "', 'MEMBER' FROM workspaces w WHERE slug = '" + workspaceSlug + "'");
    }

    private IssueResponse issue(UUID projectId, String title, String description) {
        return issueService.create(slug, projectId, pm.getId(), new CreateIssueRequest(title, description, null, null,
                null, null, null, null, null, null, null, null, null, null));
    }

    private FileResponse upload(UUID projectId, User who, UUID issueId, UploadPurpose purpose, String name,
                                String type, long size) {
        UploadTicketResponse ticket = fileService.createUpload(slug, projectId, who.getId(),
                new CreateUploadRequest(purpose, issueId, name, type, size));
        return fileService.confirmUpload(slug, projectId, ticket.file().id(), who.getId());
    }

    private String keyOf(UUID fileId) {
        return value("SELECT object_key FROM stored_files WHERE id = '" + fileId + "'");
    }

    private List<String> queued() {
        return values("SELECT object_key FROM storage_deletions ORDER BY id");
    }

    @Test
    void borrarLaTareaEncolaSusArchivosYLaLimpiezaLosBorraDeR2() {
        IssueResponse task = issue(web, "Pantalla de perfil", null);
        FileResponse pdf = upload(web, luis, task.id(), UploadPurpose.ATTACHMENT, "requisitos.pdf", "application/pdf",
                2_400_000);
        FileResponse inline = upload(web, luis, task.id(), UploadPurpose.INLINE_IMAGE, "pegada.png", "image/png", 300);
        String pdfKey = keyOf(pdf.id());
        String inlineKey = keyOf(inline.id());

        assertThat(pdf.status()).isEqualTo(FileStatus.READY);
        // El clip cuenta solo los adjuntos
        assertThat(issueService.get(slug, web, task.id()).attachmentCount()).isEqualTo(1);
        assertThat(fileService.getAttachments(slug, web, task.id())).extracting(FileResponse::id)
                .containsExactly(pdf.id());
        ActivityResponse attached = activityService.getActivity(slug, web, task.id()).stream()
                .filter(entry -> entry.field() == ActivityField.ATTACHMENT)
                .findFirst().orElseThrow();
        assertThat(attached.newValue()).isEqualTo("requisitos.pdf");
        assertThat(attached.file().size()).isEqualTo(2_400_000);

        issueService.delete(slug, web, task.id(), pm.getId());

        assertThat(value("SELECT count(*) FROM stored_files WHERE issue_id = '" + task.id() + "'")).isEqualTo("0");
        assertThat(queued()).contains(pdfKey, inlineKey);

        storageCleanup.drain();

        verify(storage).delete(pdfKey);
        verify(storage).delete(inlineKey);
        assertThat(queued()).doesNotContain(pdfKey, inlineKey);
    }

    @Test
    void borrarElWorkspaceEncolaLosArchivosDeTodosSusProyectos() {
        IssueResponse a = issue(web, "A", null);
        IssueResponse b = issue(ops, "B", null);
        String first = keyOf(upload(web, pm, a.id(), UploadPurpose.ATTACHMENT, "a.png", "image/png", 10).id());
        String second = keyOf(upload(ops, pm, b.id(), UploadPurpose.ATTACHMENT, "b.png", "image/png", 10).id());

        workspaceService.delete(slug);

        assertThat(queued()).contains(first, second);
        storageCleanup.drain();
        verify(storage).delete(first);
        verify(storage).delete(second);
    }

    @Test
    void eliminarUnAdjuntoQuedaEnElHistorialYLoBorraDeR2() {
        IssueResponse task = issue(web, "A", null);
        FileResponse file = upload(web, luis, task.id(), UploadPurpose.ATTACHMENT, "a.png", "image/png", 10);
        String key = keyOf(file.id());

        fileService.delete(slug, web, file.id(), pm.getId());

        verify(storage, timeout(5000)).delete(key);
        List<ActivityResponse> history = activityService.getActivity(slug, web, task.id());
        assertThat(history).filteredOn(entry -> entry.field() == ActivityField.ATTACHMENT)
                .extracting(ActivityResponse::newValue, ActivityResponse::oldValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a.png", null),
                        org.assertj.core.groups.Tuple.tuple(null, "a.png"));
        // La entrada de «adjuntó» ya no apunta al archivo
        assertThat(history.stream().filter(entry -> entry.field() == ActivityField.ATTACHMENT).map(ActivityResponse::file))
                .containsOnlyNulls();
    }

    @Test
    void lasUrlsSoloSonDeLoQueCadaUnoPuedeVer() {
        IssueResponse inWeb = issue(web, "Web", null);
        IssueResponse inOps = issue(ops, "Ops", null);
        UUID webFile = upload(web, pm, inWeb.id(), UploadPurpose.ATTACHMENT, "web.png", "image/png", 10).id();
        UUID opsFile = upload(ops, pm, inOps.id(), UploadPurpose.ATTACHMENT, "ops.png", "image/png", 10).id();
        User outsider = user("fuera-" + suffix + "@test.com", "Fuera");

        // Luis (solo en Web) pide también el de Ops adivinando su id: no viene
        assertThat(fileService.resolveUrls(luis.getId(), List.of(webFile, opsFile), false))
                .extracting(FileUrlResponse::id).containsExactly(webFile);
        // El PM ve todo su workspace; alguien de fuera, nada
        assertThat(fileService.resolveUrls(pm.getId(), List.of(webFile, opsFile), false))
                .extracting(FileUrlResponse::id).containsExactly(webFile, opsFile);
        assertThat(fileService.resolveUrls(outsider.getId(), List.of(webFile, opsFile), false)).isEmpty();

        // Fotos de perfil: las ve quien comparte un workspace
        UploadTicketResponse ticket = fileService.createAvatarUpload(luis.getId(),
                new AvatarUploadRequest("image/png", 500L));
        String avatarUrl = fileService.confirmAvatar(luis.getId(), ticket.file().id()).avatarUrl();
        assertThat(avatarUrl).isEqualTo("/files/" + ticket.file().id());
        assertThat(fileService.resolveUrls(pm.getId(), List.of(ticket.file().id()), false)).hasSize(1);
        assertThat(fileService.resolveUrls(luis.getId(), List.of(ticket.file().id()), false)).hasSize(1);
        assertThat(fileService.resolveUrls(outsider.getId(), List.of(ticket.file().id()), false)).isEmpty();

        // Una foto nueva reemplaza (y borra) la anterior
        String oldKey = keyOf(ticket.file().id());
        UploadTicketResponse second = fileService.createAvatarUpload(luis.getId(),
                new AvatarUploadRequest("image/png", 600L));
        fileService.confirmAvatar(luis.getId(), second.file().id());
        verify(storage, timeout(5000)).delete(oldKey);
        assertThat(userRepository.findById(luis.getId()).orElseThrow().getAvatarUrl())
                .isEqualTo("/files/" + second.file().id());
    }

    @Test
    void laCuotaCuentaLoConfirmadoYLoQueSeEstaSubiendo() {
        IssueResponse task = issue(web, "A", null);
        long quota = 2L * 1024 * 1024 * 1024;
        insertReady(web, quota - 3 * 1024 * 1024);
        // Una subida en curso de 2 MB: queda 1 MB libre
        fileService.createUpload(slug, web, pm.getId(),
                new CreateUploadRequest(UploadPurpose.ATTACHMENT, task.id(), "a.pdf", "application/pdf", 2L * 1024 * 1024));

        assertThatThrownBy(() -> fileService.createUpload(slug, web, pm.getId(),
                new CreateUploadRequest(UploadPurpose.ATTACHMENT, task.id(), "b.pdf", "application/pdf",
                        2L * 1024 * 1024)))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("Este archivo no cabe: quedan 1 MB de los 2 GB del "
                                + "workspace."));
        // El uso muestra solo lo confirmado
        assertThat(fileService.getUsage(slug).usedBytes()).isEqualTo(quota - 3 * 1024 * 1024);
        assertThat(fileService.getUsage(slug).warning()).isTrue();
    }

    @Test
    void alPasarEl90PorCientoLosPmRecibenUnAvisoEnLaBandeja() {
        IssueResponse task = issue(web, "A", null);
        long quota = 2L * 1024 * 1024 * 1024;
        insertReady(web, quota * 9 / 10 - 1024);

        upload(web, luis, task.id(), UploadPurpose.ATTACHMENT, "a.pdf", "application/pdf", 4096);
        upload(web, luis, task.id(), UploadPurpose.ATTACHMENT, "b.pdf", "application/pdf", 4096);

        List<NotificationResponse> inbox = notificationService.getMyNotifications(pm.getId(), false,
                List.of(NotificationType.STORAGE_WARNING), PageRequest.of(0, 10)).content();
        assertThat(inbox).singleElement().satisfies(notification -> {
            assertThat(notification.workspace().slug()).isEqualTo(slug);
            assertThat(notification.message()).contains("90 %").contains("Almacenamiento");
            assertThat(notification.actor()).isNull();
        });
        // A los miembros no les llega
        assertThat(notificationService.getMyNotifications(luis.getId(), false,
                List.of(NotificationType.STORAGE_WARNING), PageRequest.of(0, 10)).content()).isEmpty();

        StorageReportResponse report = fileService.getReport(slug);
        assertThat(report.byProject()).singleElement().satisfies(usage -> assertThat(usage.count()).isEqualTo(3));
        assertThat(report.largest()).hasSize(3);
        assertThat(report.largest().get(1).issue().code()).isEqualTo(task.code());
    }

    @Test
    void laLimpiezaBorraSubidasAbandonadasEImagenesQueYaNoEstanEnNingunTexto() {
        IssueResponse task = issue(web, "A", null);
        UploadTicketResponse abandoned = fileService.createUpload(slug, web, pm.getId(),
                new CreateUploadRequest(UploadPurpose.ATTACHMENT, task.id(), "a.pdf", "application/pdf", 10L));
        UploadTicketResponse recent = fileService.createUpload(slug, web, pm.getId(),
                new CreateUploadRequest(UploadPurpose.ATTACHMENT, task.id(), "b.pdf", "application/pdf", 10L));
        FileResponse unused = upload(web, pm, task.id(), UploadPurpose.INLINE_IMAGE, "sin-usar.png", "image/png", 10);
        FileResponse used = upload(web, pm, task.id(), UploadPurpose.INLINE_IMAGE, "usada.png", "image/png", 10);
        commentService.create(slug, web, task.id(), pm.getId(),
                new CommentRequest("Mira ![usada.png](/files/" + used.id() + ")"));
        sql("UPDATE stored_files SET created_at = now() - interval '25 hours' WHERE id = '" + abandoned.file().id()
                + "'");
        sql("UPDATE stored_files SET created_at = now() - interval '8 days' WHERE id IN ('" + unused.id() + "', '"
                + used.id() + "')");

        String abandonedKey = keyOf(abandoned.file().id());
        String unusedKey = keyOf(unused.id());
        String recentKey = keyOf(recent.file().id());

        storageCleanup.purgeAbandoned();

        assertThat(values("SELECT file_name FROM stored_files WHERE project_id = '" + web + "' ORDER BY file_name"))
                .containsExactly("b.pdf", "usada.png");
        verify(storage, timeout(5000)).delete(abandonedKey);
        verify(storage, timeout(5000)).delete(unusedKey);
        verify(storage, org.mockito.Mockito.never()).delete(recentKey);
    }

    @Test
    void borrarUnComentarioBorraSusImagenesSalvoLasQueSiguenEnOtroTexto() {
        IssueResponse task = issue(web, "A", null);
        FileResponse own = upload(web, pm, task.id(), UploadPurpose.INLINE_IMAGE, "propia.png", "image/png", 10);
        FileResponse shared = upload(web, pm, task.id(), UploadPurpose.INLINE_IMAGE, "compartida.png", "image/png", 10);
        UUID comment = commentService.create(slug, web, task.id(), pm.getId(), new CommentRequest(
                "![propia.png](/files/" + own.id() + ")\n\n![compartida.png](/files/" + shared.id() + ")")).id();
        commentService.create(slug, web, task.id(), luis.getId(),
                new CommentRequest("Otra vez ![compartida.png](/files/" + shared.id() + ")"));
        String ownKey = keyOf(own.id());

        commentService.delete(slug, web, task.id(), comment, pm.getId());

        assertThat(values("SELECT file_name FROM stored_files WHERE issue_id = '" + task.id() + "'"))
                .containsExactly("compartida.png");
        verify(storage, timeout(5000)).delete(ownKey);
    }

    @Test
    void lasImagenesDeNuevaTareaSeLiganALaTareaAlCrearla() {
        UploadTicketResponse ticket = fileService.createUpload(slug, web, luis.getId(),
                new CreateUploadRequest(UploadPurpose.INLINE_IMAGE, null, "pegada.png", "image/png", 10L));
        fileService.confirmUpload(slug, web, ticket.file().id(), luis.getId());

        IssueResponse task = issue(web, "Con imagen", "Así: ![pegada.png](/files/" + ticket.file().id() + ")");

        assertThat(value("SELECT issue_id FROM stored_files WHERE id = '" + ticket.file().id() + "'"))
                .isEqualTo(task.id().toString());
        // No es un adjunto
        assertThat(issueService.get(slug, web, task.id()).attachmentCount()).isZero();
    }

    // ----- SQL directo -----

    /** Un archivo confirmado de {@code size} bytes en el proyecto (para llenar el workspace sin subir nada). */
    private void insertReady(UUID projectId, long size) {
        sql("INSERT INTO stored_files (id, workspace_id, project_id, kind, status, object_key, file_name, content_type, "
                + "size_bytes) SELECT gen_random_uuid(), p.workspace_id, p.id, 'INLINE_IMAGE', 'READY', 'relleno/' || "
                + "gen_random_uuid(), 'relleno.png', 'image/png', " + size + " FROM projects p WHERE p.id = '"
                + projectId + "'");
        // Que la limpieza de imágenes sin usar no lo toque
        sql("UPDATE stored_files SET created_at = now() WHERE file_name = 'relleno.png'");
    }

    private void sql(String statement) {
        try (Connection connection = TestDatabase.connect();
             PreparedStatement query = connection.prepareStatement(statement)) {
            query.execute();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String value(String query) {
        List<String> rows = values(query);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<String> values(String query) {
        try (Connection connection = TestDatabase.connect();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet rows = statement.executeQuery()) {
            List<String> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
