package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.AvatarUploadRequest;
import com.taskflow.api.dto.request.CreateUploadRequest;
import com.taskflow.api.dto.request.CreateUploadRequest.UploadPurpose;
import com.taskflow.api.dto.response.FileResponse;
import com.taskflow.api.dto.response.FileUrlResponse;
import com.taskflow.api.dto.response.ProjectRefResponse;
import com.taskflow.api.dto.response.StorageReportResponse;
import com.taskflow.api.dto.response.StorageReportResponse.FileGroup;
import com.taskflow.api.dto.response.StorageReportResponse.LargeFile;
import com.taskflow.api.dto.response.StorageReportResponse.ProjectUsage;
import com.taskflow.api.dto.response.StorageReportResponse.TypeUsage;
import com.taskflow.api.dto.response.StorageUsageResponse;
import com.taskflow.api.dto.response.UploadTicketResponse;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.StoredFile;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.FileKind;
import com.taskflow.api.entity.enums.FileStatus;
import com.taskflow.api.entity.enums.WorkspaceRole;
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
import com.taskflow.api.service.FileService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.storage.FileTypes;
import com.taskflow.api.storage.FileTypes.FileType;
import com.taskflow.api.storage.ObjectStorage;
import com.taskflow.api.storage.ObjectStorage.ObjectInfo;
import com.taskflow.api.storage.ObjectStorage.PresignedRequest;
import com.taskflow.api.storage.StorageCleanup;
import com.taskflow.api.storage.StorageProperties;
import com.taskflow.api.storage.StorageUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** URLs que se pueden pedir de una vez. */
    static final int MAX_URLS = 100;
    /** Archivos de la lista «Archivos más pesados». */
    static final int LARGEST = 10;
    /** Referencias {@code /files/<id>} dentro de un Markdown. */
    static final Pattern REFERENCE = Pattern.compile(
            "/files/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    static final String NOT_FOUND = "Archivo no encontrado";
    static final String DELETE_FORBIDDEN = "Solo quien subió el archivo o un PM puede eliminarlo";
    static final String CONFIRM_FORBIDDEN = "Solo quien subió el archivo puede confirmarlo";
    static final String NOT_UPLOADED = "El archivo no llegó a subirse. Vuelve a intentarlo";
    static final String MISMATCH = "El archivo que llegó no es el que se declaró (otro tamaño o tipo). Vuelve a subirlo";
    public static final String STORAGE_FULL = "STORAGE_FULL";

    private final StoredFileRepository fileRepository;
    private final IssueRepository issueRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceLookup lookup;
    private final ProjectAccess projectAccess;
    private final ObjectStorage storage;
    private final StorageProperties properties;
    private final StorageCleanup cleanup;
    private final RateLimitService rateLimitService;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final IssueMapper issueMapper;

    // ----- Archivos de las tareas -----

    @Override
    @Transactional
    public UploadTicketResponse createUpload(String slug, UUID projectId, UUID userId, CreateUploadRequest request) {
        requireStorage();
        Project project = lookup.project(slug, projectId);
        boolean inline = request.purpose() == UploadPurpose.INLINE_IMAGE;
        Issue issue = null;
        if (request.issueId() != null) {
            issue = issueRepository.findByIdAndProjectId(request.issueId(), project.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
        } else if (!inline) {
            throw new BadRequestException("Indica la tarea del adjunto");
        }
        FileType type = FileTypes.validate(request.fileName(), request.contentType(), request.size(), inline);
        rateLimitService.consume(RateLimitRule.UPLOADS, userId.toString());

        // En fila por workspace: dos subidas simultáneas no pasan juntas la cuota
        Workspace workspace = project.getWorkspace();
        lookup.lockWorkspace(workspace);
        long quota = properties.workspaceQuotaBytes();
        long reserved = fileRepository.sumReservedBytes(workspace.getId());
        if (reserved + request.size() > quota) {
            throw quotaExceeded(reserved, quota);
        }

        StoredFile file = new StoredFile();
        file.setWorkspace(workspace);
        file.setProject(project);
        file.setIssue(issue);
        file.setUploadedBy(lookup.user(userId));
        file.setKind(inline ? FileKind.INLINE_IMAGE : FileKind.ATTACHMENT);
        file.setStatus(FileStatus.PENDING);
        file.setObjectKey("projects/" + project.getId() + "/" + UUID.randomUUID() + "." + type.extension());
        file.setFileName(FileTypes.cleanName(request.fileName()));
        file.setContentType(type.contentType());
        file.setSizeBytes(request.size());
        fileRepository.save(file);

        return ticket(file, StorageUsageResponse.of(reserved + request.size(), quota));
    }

    @Override
    @Transactional(noRollbackFor = UploadRejectedException.class)
    public FileResponse confirmUpload(String slug, UUID projectId, UUID fileId, UUID userId) {
        requireStorage();
        Project project = lookup.project(slug, projectId);
        StoredFile file = fileRepository.lockInProject(fileId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (!isUploader(file, userId)) {
            throw new ForbiddenException(CONFIRM_FORBIDDEN);
        }
        if (file.isReady()) {
            return fileMapper.toResponse(file);
        }
        verifyUploaded(file);
        markReady(file);
        if (file.getKind() == FileKind.ATTACHMENT && file.getIssue() != null) {
            activityService.recordAttachment(file.getIssue(), file.getUploadedBy(), file, true);
        }
        warnIfAlmostFull(project.getWorkspace(), file.getSizeBytes());
        return fileMapper.toResponse(file);
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID fileId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        StoredFile file = fileRepository.lockInProject(fileId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (!isUploader(file, userId) && !projectAccess.isManager(project.getWorkspace(), userId)) {
            throw new ForbiddenException(DELETE_FORBIDDEN);
        }
        if (file.isReady() && file.getKind() == FileKind.ATTACHMENT && file.getIssue() != null) {
            activityService.recordAttachment(file.getIssue(), lookup.user(userId), file, false);
        }
        fileRepository.delete(file);
        cleanup.drainAfterCommit();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FileResponse> getAttachments(String slug, UUID projectId, UUID issueId) {
        Project project = lookup.project(slug, projectId);
        Issue issue = issueRepository.findByIdAndProjectId(issueId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
        return fileRepository.findAttachments(issue.getId()).stream().map(fileMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public void claimInlineImages(Issue issue, String markdown) {
        Set<UUID> ids = references(markdown);
        if (!ids.isEmpty()) {
            fileRepository.claimInlineImages(ids, issue.getProject(), issue);
        }
    }

    @Override
    @Transactional
    public void releaseInlineImages(Project project, String markdown) {
        Set<UUID> ids = references(markdown);
        if (!ids.isEmpty() && fileRepository.deleteUnreferencedInlineImagesIn(ids, project.getId()) > 0) {
            cleanup.drainAfterCommit();
        }
    }

    // ----- URLs para ver y descargar -----

    @Override
    @Transactional(readOnly = true)
    public List<FileUrlResponse> resolveUrls(UUID userId, Collection<UUID> ids, boolean download) {
        Set<UUID> wanted = ids == null ? Set.of()
                : ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (wanted.isEmpty()) {
            return List.of();
        }
        if (wanted.size() > MAX_URLS) {
            throw new BadRequestException("Se pueden pedir hasta " + MAX_URLS + " archivos a la vez");
        }
        requireStorage();
        Map<UUID, StoredFile> files = fileRepository.findReadyByIds(wanted).stream()
                .collect(Collectors.toMap(StoredFile::getId, Function.identity()));

        Set<UUID> projectIds = files.values().stream()
                .filter(file -> file.getProject() != null)
                .map(file -> file.getProject().getId())
                .collect(Collectors.toSet());
        Set<UUID> projects = projectIds.isEmpty() ? Set.of()
                : new HashSet<>(fileRepository.findAccessibleProjectIds(projectIds, userId));

        Set<UUID> owners = files.values().stream()
                .filter(file -> file.getKind() == FileKind.AVATAR && file.getUploadedBy() != null)
                .map(file -> file.getUploadedBy().getId())
                .collect(Collectors.toSet());
        Set<UUID> people = new HashSet<>();
        if (owners.contains(userId)) {
            people.add(userId);
        }
        owners.remove(userId);
        if (!owners.isEmpty()) {
            people.addAll(fileRepository.findUsersSharingWorkspace(owners, userId));
        }

        return wanted.stream()
                .map(files::get)
                .filter(Objects::nonNull)
                .filter(file -> file.getProject() != null
                        ? projects.contains(file.getProject().getId())
                        : file.getUploadedBy() != null && people.contains(file.getUploadedBy().getId()))
                .map(file -> url(file, download))
                .toList();
    }

    private FileUrlResponse url(StoredFile file, boolean download) {
        boolean inline = !download && (file.isImage() || "application/pdf".equals(file.getContentType()));
        PresignedRequest get = storage.presignDownload(file.getObjectKey(), file.getContentType(), file.getFileName(),
                inline, properties.downloadUrlTtl());
        return new FileUrlResponse(file.getId(), get.url(), get.expiresAt(), file.getFileName(), file.getContentType(),
                file.getSizeBytes(), file.isImage());
    }

    // ----- Espacio del workspace -----

    @Override
    @Transactional(readOnly = true)
    public StorageUsageResponse getUsage(String slug) {
        Workspace workspace = lookup.workspace(slug);
        return StorageUsageResponse.of(fileRepository.sumReadyBytes(workspace.getId()),
                properties.workspaceQuotaBytes());
    }

    @Override
    @Transactional(readOnly = true)
    public StorageReportResponse getReport(String slug) {
        Workspace workspace = lookup.workspace(slug);
        StorageUsageResponse usage = StorageUsageResponse.of(fileRepository.sumReadyBytes(workspace.getId()),
                properties.workspaceQuotaBytes());

        Map<FileGroup, long[]> byGroup = new EnumMap<>(FileGroup.class);
        for (FileGroup group : FileGroup.values()) {
            byGroup.put(group, new long[2]);
        }
        for (Object[] row : fileRepository.usageByContentType(workspace.getId())) {
            long[] totals = byGroup.get(FileTypes.group((String) row[0]));
            totals[0] += ((Number) row[1]).longValue();
            totals[1] += ((Number) row[2]).longValue();
        }
        List<TypeUsage> byType = byGroup.entrySet().stream()
                .map(entry -> new TypeUsage(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();

        List<Object[]> projectRows = fileRepository.usageByProject(workspace.getId());
        Map<UUID, Project> projects = projectRepository.findAllById(projectRows.stream()
                        .map(row -> (UUID) row[0]).toList()).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        List<ProjectUsage> byProject = projectRows.stream()
                .filter(row -> projects.containsKey((UUID) row[0]))
                .map(row -> new ProjectUsage(projectMapper.toRef(projects.get((UUID) row[0])),
                        ((Number) row[1]).longValue(), ((Number) row[2]).longValue()))
                .toList();

        List<LargeFile> largest = fileRepository.findLargest(workspace.getId(), Limit.of(LARGEST)).stream()
                .map(file -> {
                    ProjectRefResponse project = projectMapper.toRef(file.getProject());
                    return new LargeFile(file.getId(), file.getFileName(), file.getContentType(),
                            file.getSizeBytes(), file.isImage(), project,
                            file.getIssue() == null ? null : issueMapper.toSummary(file.getIssue()),
                            userMapper.toSummary(file.getUploadedBy()), file.getCreatedAt());
                })
                .toList();
        return new StorageReportResponse(usage, byType, byProject, largest);
    }

    // ----- Foto de perfil -----

    @Override
    @Transactional
    public UploadTicketResponse createAvatarUpload(UUID userId, AvatarUploadRequest request) {
        requireStorage();
        FileType type = FileTypes.validateAvatar(request.contentType(), request.size());
        rateLimitService.consume(RateLimitRule.UPLOADS, userId.toString());
        StoredFile file = new StoredFile();
        file.setUploadedBy(lookup.user(userId));
        file.setKind(FileKind.AVATAR);
        file.setStatus(FileStatus.PENDING);
        file.setObjectKey("avatars/" + userId + "/" + UUID.randomUUID() + "." + type.extension());
        file.setFileName("foto-de-perfil." + type.extension());
        file.setContentType(type.contentType());
        file.setSizeBytes(request.size());
        fileRepository.save(file);
        return ticket(file, null);
    }

    @Override
    @Transactional(noRollbackFor = UploadRejectedException.class)
    public UserResponse confirmAvatar(UUID userId, UUID fileId) {
        requireStorage();
        StoredFile file = fileRepository.lockAvatar(fileId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (!file.isReady()) {
            verifyUploaded(file);
            markReady(file);
        }
        User user = lookup.user(userId);
        user.setAvatarUrl(FileMapper.reference(file.getId()));
        List<StoredFile> previous = fileRepository.findAllByUploadedByIdAndKind(userId, FileKind.AVATAR).stream()
                .filter(other -> !other.getId().equals(file.getId()))
                .toList();
        if (!previous.isEmpty()) {
            fileRepository.deleteAll(previous);
            cleanup.drainAfterCommit();
        }
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse removeAvatar(UUID userId) {
        User user = lookup.user(userId);
        user.setAvatarUrl(null);
        deleteAvatars(userId);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void deleteAvatars(UUID userId) {
        List<StoredFile> avatars = fileRepository.findAllByUploadedByIdAndKind(userId, FileKind.AVATAR);
        if (!avatars.isEmpty()) {
            fileRepository.deleteAll(avatars);
            cleanup.drainAfterCommit();
        }
    }

    // ----- Comunes -----

    private UploadTicketResponse ticket(StoredFile file, StorageUsageResponse usage) {
        PresignedRequest put = storage.presignUpload(file.getObjectKey(), file.getContentType(), file.getSizeBytes(),
                properties.uploadUrlTtl());
        return new UploadTicketResponse(fileMapper.toResponse(file), put.url(), "PUT", put.headers(), put.expiresAt(),
                usage);
    }

    /**
     * Comprueba en R2 el objeto subido: que exista, con el tamaño y el tipo firmados, y que su contenido empiece como
     * ese tipo. Si no coincide, borra el archivo (fila y objeto) y responde 400.
     */
    private void verifyUploaded(StoredFile file) {
        ObjectInfo info = storage.head(file.getObjectKey())
                .orElseThrow(() -> new ConflictException(NOT_UPLOADED, "UPLOAD_MISSING"));
        boolean sameType = info.contentType() != null
                && info.contentType().split(";")[0].strip().toLowerCase(Locale.ROOT).equals(file.getContentType());
        if (info.size() != file.getSizeBytes() || !sameType) {
            reject(file, MISMATCH);
        }
        byte[] head = storage.readPrefix(file.getObjectKey(), FileTypes.SIGNATURE_BYTES);
        if (!FileTypes.contentMatches(file.getContentType(), head)) {
            reject(file, "El contenido de «" + file.getFileName() + "» no corresponde a su tipo. Vuelve a subirlo");
        }
    }

    private void reject(StoredFile file, String message) {
        // La transacción se confirma igual (noRollbackFor): la fila se borra y su objeto pasa a la cola de borrado
        fileRepository.delete(file);
        cleanup.drainAfterCommit();
        throw new UploadRejectedException(message);
    }

    private static void markReady(StoredFile file) {
        file.setStatus(FileStatus.READY);
        file.setConfirmedAt(Instant.now());
    }

    /** Si este archivo hizo pasar al workspace del 90 %, avisa a sus project managers (en la Bandeja). */
    private void warnIfAlmostFull(Workspace workspace, long added) {
        long quota = properties.workspaceQuotaBytes();
        long now = fileRepository.sumReadyBytes(workspace.getId());
        StorageUsageResponse after = StorageUsageResponse.of(now, quota);
        StorageUsageResponse before = StorageUsageResponse.of(now - added, quota);
        if (after.warning() && !before.warning()) {
            notificationService.notifyStorageWarning(workspace,
                    workspaceMemberRepository.findUsersWithRole(workspace.getId(), WorkspaceRole.OWNER),
                    after.percent(), FileTypes.formatSize(quota));
        }
    }

    private ConflictException quotaExceeded(long reserved, long quota) {
        String limit = FileTypes.formatSize(quota);
        if (reserved >= quota) {
            return new ConflictException("El workspace llegó a su límite de " + limit + " para archivos. Un PM puede "
                    + "liberar espacio en Ajustes › Almacenamiento.", STORAGE_FULL);
        }
        return new ConflictException("Este archivo no cabe: quedan " + FileTypes.formatSize(quota - reserved)
                + " de los " + limit + " del workspace.", STORAGE_FULL);
    }

    private static boolean isUploader(StoredFile file, UUID userId) {
        return file.getUploadedBy() != null && file.getUploadedBy().getId().equals(userId);
    }

    private void requireStorage() {
        if (!storage.enabled()) {
            throw new StorageUnavailableException(StorageUnavailableException.NOT_CONFIGURED);
        }
    }

    /** Ids de las referencias {@code /files/<id>} de un Markdown. */
    static Set<UUID> references(String markdown) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (markdown == null || markdown.isEmpty()) {
            return ids;
        }
        Matcher matcher = REFERENCE.matcher(markdown);
        while (matcher.find()) {
            ids.add(UUID.fromString(matcher.group(1)));
        }
        return ids;
    }
}
