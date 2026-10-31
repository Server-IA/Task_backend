package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.AvatarUploadRequest;
import com.taskflow.api.dto.request.CreateUploadRequest;
import com.taskflow.api.dto.request.CreateUploadRequest.UploadPurpose;
import com.taskflow.api.dto.response.FileResponse;
import com.taskflow.api.dto.response.FileUrlResponse;
import com.taskflow.api.dto.response.StorageReportResponse;
import com.taskflow.api.dto.response.StorageReportResponse.FileGroup;
import com.taskflow.api.dto.response.UploadTicketResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.StoredFile;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.FileKind;
import com.taskflow.api.entity.enums.FileStatus;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.ApiException;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.exception.UploadRejectedException;
import com.taskflow.api.mapper.FileMapper;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.ratelimit.RateLimitRule;
import com.taskflow.api.ratelimit.RateLimitService;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StoredFileRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.storage.ObjectStorage;
import com.taskflow.api.storage.ObjectStorage.ObjectInfo;
import com.taskflow.api.storage.ObjectStorage.PresignedRequest;
import com.taskflow.api.storage.StorageCleanup;
import com.taskflow.api.storage.StorageProperties;
import com.taskflow.api.storage.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.taskflow.api.storage.FileTypes.MB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Archivos: validación y cuota al pedir la subida, comprobación en R2 al confirmarla, permisos (también con el id de un
 * archivo de otro proyecto) y borrado. R2 es un {@link ObjectStorage} simulado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceImplTest {

    private static final String SLUG = "mi-equipo";
    private static final long QUOTA = 2L * 1024 * MB;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Mock
    private StoredFileRepository fileRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ObjectStorage storage;
    @Mock
    private StorageCleanup cleanup;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ActivityService activityService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileMapper fileMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private IssueMapper issueMapper;

    private FileServiceImpl service;
    private Workspace workspace;
    private Project project;
    private Issue issue;
    private User ana;
    private User luis;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties("cuenta", "id", "secreto", "taskflow-files", "", QUOTA,
                Duration.ofMinutes(15), Duration.ofHours(1));
        service = new FileServiceImpl(fileRepository, issueRepository, projectRepository, workspaceMemberRepository,
                lookup, projectAccess, storage, properties, cleanup, rateLimitService, activityService,
                notificationService, fileMapper, userMapper, projectMapper, issueMapper);

        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("Equipo Diseño");
        workspace.setSlug(SLUG);
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setWorkspace(workspace);
        project.setIdentifier("WEB");
        issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        ana = user("Ana");
        luis = user("Luis");

        when(storage.enabled()).thenReturn(true);
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(lookup.user(ana.getId())).thenReturn(ana);
        when(lookup.user(luis.getId())).thenReturn(luis);
        when(issueRepository.findByIdAndProjectId(issue.getId(), project.getId())).thenReturn(Optional.of(issue));
        when(storage.presignUpload(anyString(), anyString(), anyLong(), any())).thenAnswer(call -> new PresignedRequest(
                "https://r2/" + call.getArgument(0), Map.of("Content-Type", call.getArgument(1)),
                Instant.now().plus(Duration.ofMinutes(15))));
        when(storage.presignDownload(anyString(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(call -> new PresignedRequest("https://r2/" + call.getArgument(0) + "?inline="
                        + call.getArgument(3), Map.of(), Instant.now().plus(Duration.ofHours(1))));
        when(fileMapper.toResponse(any())).thenAnswer(call -> {
            StoredFile file = call.getArgument(0);
            return new FileResponse(file.getId(), file.getKind(), file.getStatus(), file.getFileName(),
                    file.getContentType(), file.getSizeBytes(), file.isImage(), null, null, null, null,
                    FileMapper.reference(file.getId()));
        });
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    private StoredFile file(FileKind kind, FileStatus status, User uploader, String name, String type, long size) {
        StoredFile file = new StoredFile();
        file.setId(UUID.randomUUID());
        file.setKind(kind);
        file.setStatus(status);
        file.setUploadedBy(uploader);
        file.setFileName(name);
        file.setContentType(type);
        file.setSizeBytes(size);
        file.setObjectKey("projects/" + project.getId() + "/" + UUID.randomUUID());
        if (kind != FileKind.AVATAR) {
            file.setWorkspace(workspace);
            file.setProject(project);
            file.setIssue(issue);
        }
        return file;
    }

    private StoredFile pendingPng(User uploader) {
        StoredFile file = file(FileKind.ATTACHMENT, FileStatus.PENDING, uploader, "mockup.png", "image/png", 1000);
        when(fileRepository.lockInProject(file.getId(), project.getId())).thenReturn(Optional.of(file));
        return file;
    }

    private static CreateUploadRequest attachment(UUID issueId, String name, String type, long size) {
        return new CreateUploadRequest(UploadPurpose.ATTACHMENT, issueId, name, type, size);
    }

    @Nested
    class PedirSubida {

        @Test
        void firmaLaSubidaConElTipoCanonicoYLaGuardaPendiente() {
            when(fileRepository.sumReservedBytes(workspace.getId())).thenReturn(100 * MB);

            UploadTicketResponse ticket = service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "iconos.zip", "application/x-zip-compressed", 2 * MB));

            ArgumentCaptor<StoredFile> saved = ArgumentCaptor.forClass(StoredFile.class);
            verify(fileRepository).save(saved.capture());
            StoredFile file = saved.getValue();
            assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
            assertThat(file.getKind()).isEqualTo(FileKind.ATTACHMENT);
            assertThat(file.getContentType()).isEqualTo("application/zip");
            assertThat(file.getObjectKey()).startsWith("projects/" + project.getId() + "/").endsWith(".zip");
            assertThat(file.getIssue()).isSameAs(issue);
            assertThat(file.getUploadedBy()).isSameAs(ana);
            verify(storage).presignUpload(file.getObjectKey(), "application/zip", 2 * MB, Duration.ofMinutes(15));
            assertThat(ticket.method()).isEqualTo("PUT");
            assertThat(ticket.headers()).containsEntry("Content-Type", "application/zip");
            assertThat(ticket.storage().usedBytes()).isEqualTo(102 * MB);
            // En fila por workspace y con el límite de subidas por persona
            verify(lookup).lockWorkspace(workspace);
            verify(rateLimitService).consume(RateLimitRule.UPLOADS, ana.getId().toString());
        }

        @Test
        void rechazaTiposYTamanosNoPermitidosSinFirmarNada() {
            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "instalador.exe", "application/x-msdownload", 100)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Este tipo de archivo no se puede adjuntar.");
            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "hero.png", "image/png", 6 * MB)))
                    .hasMessage("Pesa 6 MB y el límite es 5 MB.");
            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "grabacion.zip", "application/zip", 38 * MB)))
                    .hasMessage("Pesa 38 MB y el límite es 10 MB.");
            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "foto.png", "text/html", 100)))
                    .hasMessage("El tipo del archivo no coincide con su extensión.");

            verify(fileRepository, never()).save(any());
            verify(storage, never()).presignUpload(anyString(), anyString(), anyLong(), any());
            verifyNoInteractions(rateLimitService);
        }

        @Test
        void enUnTextoSoloImagenesYSinTareaSoloEnNuevaTarea() {
            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    new CreateUploadRequest(UploadPurpose.INLINE_IMAGE, issue.getId(), "a.pdf", "application/pdf", 9L)))
                    .hasMessage("Solo se pueden pegar imágenes PNG, JPG, GIF o WebP.");
            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(null, "a.pdf", "application/pdf", 9)))
                    .hasMessage("Indica la tarea del adjunto");

            // Imagen de «Nueva tarea»: todavía sin tarea
            service.createUpload(SLUG, project.getId(), ana.getId(),
                    new CreateUploadRequest(UploadPurpose.INLINE_IMAGE, null, "pegada.png", "image/png", 9L));
            ArgumentCaptor<StoredFile> saved = ArgumentCaptor.forClass(StoredFile.class);
            verify(fileRepository).save(saved.capture());
            assertThat(saved.getValue().getKind()).isEqualTo(FileKind.INLINE_IMAGE);
            assertThat(saved.getValue().getIssue()).isNull();
        }

        @Test
        void unaTareaDeOtroProyectoNoSeEncuentra() {
            UUID otherIssue = UUID.randomUUID();
            when(issueRepository.findByIdAndProjectId(otherIssue, project.getId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(otherIssue, "a.pdf", "application/pdf", 9)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void conElWorkspaceLlenoNoSePuedeSubir() {
            when(fileRepository.sumReservedBytes(workspace.getId())).thenReturn(QUOTA);

            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "a.pdf", "application/pdf", 1)))
                    .isInstanceOfSatisfying(ConflictException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(FileServiceImpl.STORAGE_FULL);
                        assertThat(ex.getMessage()).contains("llegó a su límite de 2 GB");
                    });
            verify(fileRepository, never()).save(any());
        }

        @Test
        void unArchivoQueNoCabeDiceCuantoQueda() {
            when(fileRepository.sumReservedBytes(workspace.getId())).thenReturn(QUOTA - 3 * MB);

            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "a.pdf", "application/pdf", 4 * MB)))
                    .isInstanceOfSatisfying(ConflictException.class, ex ->
                            assertThat(ex.getMessage()).isEqualTo("Este archivo no cabe: quedan 3 MB de los 2 GB del "
                                    + "workspace."));
        }

        @Test
        void sinR2Responde503() {
            when(storage.enabled()).thenReturn(false);

            assertThatThrownBy(() -> service.createUpload(SLUG, project.getId(), ana.getId(),
                    attachment(issue.getId(), "a.pdf", "application/pdf", 9)))
                    .isInstanceOf(StorageUnavailableException.class)
                    .extracting(ex -> ((ApiException) ex).getStatus().value()).isEqualTo(503);
        }
    }

    @Nested
    class Confirmar {

        @Test
        void compruebaEnR2YQuedaListoConSuEntradaEnElHistorial() {
            StoredFile file = pendingPng(ana);
            when(storage.head(file.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(1000, "image/png")));
            when(storage.readPrefix(file.getObjectKey(), 16)).thenReturn(PNG);
            when(fileRepository.sumReadyBytes(workspace.getId())).thenReturn(10 * MB);

            FileResponse response = service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId());

            assertThat(response.status()).isEqualTo(FileStatus.READY);
            assertThat(file.getConfirmedAt()).isNotNull();
            verify(activityService).recordAttachment(issue, ana, file, true);
            verify(notificationService, never()).notifyStorageWarning(any(), any(), anyInt(), any());
            verify(fileRepository, never()).delete(any());
        }

        @Test
        void otroTamanoUOtroTipoBorraElArchivo() {
            StoredFile file = pendingPng(ana);
            when(storage.head(file.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(999_999, "image/png")));

            assertThatThrownBy(() -> service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId()))
                    .isInstanceOfSatisfying(UploadRejectedException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(UploadRejectedException.CODE);
                        assertThat(ex.getStatus().value()).isEqualTo(400);
                    });
            verify(fileRepository).delete(file);
            verify(cleanup).drainAfterCommit();
            verify(activityService, never()).recordAttachment(any(), any(), any(), anyBoolean());

            StoredFile other = pendingPng(ana);
            when(storage.head(other.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(1000, "text/html")));
            assertThatThrownBy(() -> service.confirmUpload(SLUG, project.getId(), other.getId(), ana.getId()))
                    .isInstanceOf(UploadRejectedException.class);
            verify(fileRepository).delete(other);
        }

        @Test
        void unContenidoQueNoEsDeSuTipoBorraElArchivo() {
            StoredFile file = pendingPng(ana);
            when(storage.head(file.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(1000, "image/png")));
            when(storage.readPrefix(file.getObjectKey(), 16))
                    .thenReturn("<html><script>".getBytes(StandardCharsets.US_ASCII));

            assertThatThrownBy(() -> service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId()))
                    .isInstanceOf(UploadRejectedException.class)
                    .hasMessageContaining("no corresponde a su tipo");
            verify(fileRepository).delete(file);
            assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        }

        @Test
        void siTodaviaNoEstaEnR2NoLoBorra() {
            StoredFile file = pendingPng(ana);
            when(storage.head(file.getObjectKey())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId()))
                    .isInstanceOfSatisfying(ConflictException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo("UPLOAD_MISSING"));
            verify(fileRepository, never()).delete(any());
        }

        @Test
        void soloQuienLoSubioLoConfirma() {
            StoredFile file = pendingPng(ana);

            assertThatThrownBy(() -> service.confirmUpload(SLUG, project.getId(), file.getId(), luis.getId()))
                    .isInstanceOf(ForbiddenException.class);
            verify(storage, never()).head(anyString());
        }

        @Test
        void confirmarDosVecesNoRepiteNada() {
            StoredFile file = pendingPng(ana);
            file.setStatus(FileStatus.READY);

            service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId());

            verify(storage, never()).head(anyString());
            verifyNoInteractions(activityService);
        }

        @Test
        void unaImagenDeUnTextoNoVaAlHistorial() {
            StoredFile file = pendingPng(ana);
            file.setKind(FileKind.INLINE_IMAGE);
            when(storage.head(file.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(1000, "image/png")));
            when(storage.readPrefix(file.getObjectKey(), 16)).thenReturn(PNG);

            service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId());

            assertThat(file.isReady()).isTrue();
            verifyNoInteractions(activityService);
        }

        @Test
        void alPasarEl90PorCientoAvisaALosPm() {
            StoredFile file = pendingPng(ana);
            file.setSizeBytes(20 * MB);
            when(storage.head(file.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(20 * MB, "image/png")));
            when(storage.readPrefix(file.getObjectKey(), 16)).thenReturn(PNG);
            long ninety = QUOTA * 9 / 10;
            when(fileRepository.sumReadyBytes(workspace.getId())).thenReturn(ninety + 10 * MB);
            List<User> managers = List.of(luis);
            when(workspaceMemberRepository.findUsersWithRole(workspace.getId(), WorkspaceRole.OWNER))
                    .thenReturn(managers);

            service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId());

            verify(notificationService).notifyStorageWarning(workspace, managers, 90, "2 GB");
        }

        @Test
        void siYaEstabaSobreEl90NoAvisaOtraVez() {
            StoredFile file = pendingPng(ana);
            when(storage.head(file.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(1000, "image/png")));
            when(storage.readPrefix(file.getObjectKey(), 16)).thenReturn(PNG);
            when(fileRepository.sumReadyBytes(workspace.getId())).thenReturn(QUOTA * 95 / 100);

            service.confirmUpload(SLUG, project.getId(), file.getId(), ana.getId());

            verify(notificationService, never()).notifyStorageWarning(any(), any(), anyInt(), any());
        }
    }

    @Nested
    class Eliminar {

        @Test
        void quienLoSubioLoEliminaYQuedaEnElHistorial() {
            StoredFile file = pendingPng(ana);
            file.setStatus(FileStatus.READY);

            service.delete(SLUG, project.getId(), file.getId(), ana.getId());

            verify(activityService).recordAttachment(issue, ana, file, false);
            verify(fileRepository).delete(file);
            verify(cleanup).drainAfterCommit();
        }

        @Test
        void unPmEliminaElDeOtro() {
            StoredFile file = pendingPng(ana);
            file.setStatus(FileStatus.READY);
            when(projectAccess.isManager(workspace, luis.getId())).thenReturn(true);

            service.delete(SLUG, project.getId(), file.getId(), luis.getId());

            verify(fileRepository).delete(file);
            verify(activityService).recordAttachment(issue, luis, file, false);
        }

        @Test
        void otroMiembroNoPuede() {
            StoredFile file = pendingPng(ana);
            file.setStatus(FileStatus.READY);
            when(projectAccess.isManager(workspace, luis.getId())).thenReturn(false);

            assertThatThrownBy(() -> service.delete(SLUG, project.getId(), file.getId(), luis.getId()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo quien subió el archivo o un PM puede eliminarlo");
            verify(fileRepository, never()).delete(any());
        }

        @Test
        void elIdDeUnArchivoDeOtroProyectoNoSeEncuentra() {
            // El archivo existe, pero en otro proyecto: la búsqueda va filtrada por el proyecto de la URL
            UUID foreign = UUID.randomUUID();
            when(fileRepository.lockInProject(foreign, project.getId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(SLUG, project.getId(), foreign, ana.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.confirmUpload(SLUG, project.getId(), foreign, ana.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(fileRepository, never()).delete(any());
        }

        @Test
        void cancelarUnaSubidaEnCursoNoVaAlHistorial() {
            StoredFile file = pendingPng(ana);

            service.delete(SLUG, project.getId(), file.getId(), ana.getId());

            verify(fileRepository).delete(file);
            verifyNoInteractions(activityService);
        }
    }

    @Nested
    class Urls {

        private StoredFile readyIn(Project owner, String name, String type) {
            StoredFile file = file(FileKind.ATTACHMENT, FileStatus.READY, ana, name, type, 100);
            file.setProject(owner);
            return file;
        }

        @Test
        void soloFirmaLosDeProyectosAccesibles() {
            Project other = new Project();
            other.setId(UUID.randomUUID());
            StoredFile mine = readyIn(project, "mockup.png", "image/png");
            StoredFile foreign = readyIn(other, "secreto.pdf", "application/pdf");
            UUID missing = UUID.randomUUID();
            when(fileRepository.findReadyByIds(anyCollection())).thenReturn(List.of(mine, foreign));
            when(fileRepository.findAccessibleProjectIds(anyCollection(), eq(luis.getId())))
                    .thenReturn(List.of(project.getId()));

            List<FileUrlResponse> urls = service.resolveUrls(luis.getId(), List.of(foreign.getId(), mine.getId(),
                    missing), false);

            assertThat(urls).extracting(FileUrlResponse::id).containsExactly(mine.getId());
            verify(storage, never()).presignDownload(eq(foreign.getObjectKey()), anyString(), anyString(),
                    anyBoolean(), any());
        }

        @Test
        void imagenesYPdfSeAbrenYLoDemasSeDescarga() {
            StoredFile png = readyIn(project, "a.png", "image/png");
            StoredFile pdf = readyIn(project, "b.pdf", "application/pdf");
            StoredFile zip = readyIn(project, "c.zip", "application/zip");
            when(fileRepository.findReadyByIds(anyCollection())).thenReturn(List.of(png, pdf, zip));
            when(fileRepository.findAccessibleProjectIds(anyCollection(), any())).thenReturn(List.of(project.getId()));

            service.resolveUrls(ana.getId(), List.of(png.getId(), pdf.getId(), zip.getId()), false);
            verify(storage).presignDownload(png.getObjectKey(), "image/png", "a.png", true, Duration.ofHours(1));
            verify(storage).presignDownload(pdf.getObjectKey(), "application/pdf", "b.pdf", true, Duration.ofHours(1));
            verify(storage).presignDownload(zip.getObjectKey(), "application/zip", "c.zip", false, Duration.ofHours(1));

            service.resolveUrls(ana.getId(), List.of(png.getId()), true);
            verify(storage).presignDownload(png.getObjectKey(), "image/png", "a.png", false, Duration.ofHours(1));
        }

        @Test
        void lasFotosDePerfilSoloDeQuienComparteUnWorkspace() {
            User stranger = user("Externo");
            StoredFile mine = file(FileKind.AVATAR, FileStatus.READY, ana, "foto.webp", "image/webp", 100);
            StoredFile teammate = file(FileKind.AVATAR, FileStatus.READY, luis, "foto.webp", "image/webp", 100);
            StoredFile strangers = file(FileKind.AVATAR, FileStatus.READY, stranger, "foto.webp", "image/webp", 100);
            when(fileRepository.findReadyByIds(anyCollection())).thenReturn(List.of(mine, teammate, strangers));
            when(fileRepository.findUsersSharingWorkspace(anyCollection(), eq(ana.getId())))
                    .thenReturn(List.of(luis.getId()));

            List<FileUrlResponse> urls = service.resolveUrls(ana.getId(),
                    List.of(mine.getId(), teammate.getId(), strangers.getId()), false);

            assertThat(urls).extracting(FileUrlResponse::id).containsExactly(mine.getId(), teammate.getId());
        }

        @Test
        void hasta100DeUnaVez() {
            List<UUID> ids = new ArrayList<>(IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList());

            assertThatThrownBy(() -> service.resolveUrls(ana.getId(), ids, false))
                    .isInstanceOf(BadRequestException.class);
            assertThat(service.resolveUrls(ana.getId(), List.of(), false)).isEmpty();
        }
    }

    @Nested
    class Almacenamiento {

        @Test
        void agrupaPorTipoYPorProyecto() {
            when(fileRepository.sumReadyBytes(workspace.getId())).thenReturn(1200 * MB);
            when(fileRepository.usageByContentType(workspace.getId())).thenReturn(List.of(
                    new Object[] {"image/png", 600 * MB, 10L},
                    new Object[] {"image/webp", 120 * MB, 5L},
                    new Object[] {"application/pdf", 390 * MB, 7L},
                    new Object[] {"application/zip", 90 * MB, 2L}));
            when(fileRepository.usageByProject(workspace.getId()))
                    .thenReturn(List.<Object[]>of(new Object[] {project.getId(), 1200 * MB, 24L}));
            when(projectRepository.findAllById(any())).thenReturn(List.of(project));

            StorageReportResponse report = service.getReport(SLUG);

            assertThat(report.usage().percent()).isEqualTo(58);
            assertThat(report.byType()).extracting(type -> type.type() + "=" + type.bytes() / MB + "/" + type.count())
                    .containsExactly(FileGroup.IMAGES + "=720/15", FileGroup.DOCUMENTS + "=390/7",
                            FileGroup.OTHER + "=90/2");
            assertThat(report.byProject()).singleElement()
                    .satisfies(usage -> assertThat(usage.count()).isEqualTo(24));
        }
    }

    @Nested
    class FotoDePerfil {

        @Test
        void soloPngJpgOWebp() {
            assertThatThrownBy(() -> service.createAvatarUpload(ana.getId(), new AvatarUploadRequest("image/gif", 10L)))
                    .hasMessage("La foto de perfil debe ser PNG, JPG o WebP.");

            service.createAvatarUpload(ana.getId(), new AvatarUploadRequest("image/webp", 40_000L));
            ArgumentCaptor<StoredFile> saved = ArgumentCaptor.forClass(StoredFile.class);
            verify(fileRepository).save(saved.capture());
            assertThat(saved.getValue().getKind()).isEqualTo(FileKind.AVATAR);
            assertThat(saved.getValue().getWorkspace()).isNull();
            assertThat(saved.getValue().getObjectKey()).startsWith("avatars/" + ana.getId() + "/").endsWith(".webp");
        }

        @Test
        void confirmarlaLaPoneEnElPerfilYBorraLaAnterior() {
            StoredFile current = file(FileKind.AVATAR, FileStatus.PENDING, ana, "foto.webp", "image/webp", 100);
            StoredFile old = file(FileKind.AVATAR, FileStatus.READY, ana, "foto.webp", "image/webp", 100);
            when(fileRepository.lockAvatar(current.getId(), ana.getId())).thenReturn(Optional.of(current));
            when(storage.head(current.getObjectKey())).thenReturn(Optional.of(new ObjectInfo(100, "image/webp")));
            when(storage.readPrefix(current.getObjectKey(), 16))
                    .thenReturn("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1));
            when(fileRepository.findAllByUploadedByIdAndKind(ana.getId(), FileKind.AVATAR))
                    .thenReturn(List.of(current, old));

            service.confirmAvatar(ana.getId(), current.getId());

            assertThat(ana.getAvatarUrl()).isEqualTo("/files/" + current.getId());
            assertThat(current.isReady()).isTrue();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<StoredFile>> deleted = ArgumentCaptor.forClass(Collection.class);
            verify(fileRepository).deleteAll(deleted.capture());
            assertThat(deleted.getValue()).containsExactly(old);
            verify(cleanup).drainAfterCommit();
        }

        @Test
        void laFotoDeOtraPersonaNoSeEncuentra() {
            UUID other = UUID.randomUUID();
            when(fileRepository.lockAvatar(other, ana.getId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmAvatar(ana.getId(), other))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void quitarlaVuelveALasIniciales() {
            ana.setAvatarUrl("/files/" + UUID.randomUUID());
            StoredFile old = file(FileKind.AVATAR, FileStatus.READY, ana, "foto.webp", "image/webp", 100);
            when(fileRepository.findAllByUploadedByIdAndKind(ana.getId(), FileKind.AVATAR)).thenReturn(List.of(old));

            service.removeAvatar(ana.getId());

            assertThat(ana.getAvatarUrl()).isNull();
            verify(fileRepository).deleteAll(List.of(old));
        }
    }

    @Test
    void borrarUnTextoLiberaSusImagenes() {
        UUID image = UUID.randomUUID();
        when(fileRepository.deleteUnreferencedInlineImagesIn(any(), eq(project.getId()))).thenReturn(1);

        service.releaseInlineImages(project, "![a.png](/files/" + image + ")");

        verify(fileRepository).deleteUnreferencedInlineImagesIn(List.of(image).stream().collect(
                java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)), project.getId());
        verify(cleanup).drainAfterCommit();

        service.releaseInlineImages(project, "sin imágenes");
        verify(fileRepository).deleteUnreferencedInlineImagesIn(any(), any());
    }

    @Test
    void ligaALaTareaLasImagenesDeSuDescripcion() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        service.claimInlineImages(issue, "Mira ![a.png](/files/" + first + ")\n\n![b](/files/" + second
                + ") y otra vez ![a.png](/files/" + first + ")");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(fileRepository).claimInlineImages(ids.capture(), eq(project), eq(issue));
        assertThat(ids.getValue()).containsExactly(first, second);

        service.claimInlineImages(issue, "Sin imágenes");
        service.claimInlineImages(issue, null);
        verify(fileRepository).claimInlineImages(anyCollection(), any(), any());
    }
}
