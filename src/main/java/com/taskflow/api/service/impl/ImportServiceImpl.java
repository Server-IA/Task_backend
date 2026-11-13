package com.taskflow.api.service.impl;

import com.taskflow.api.security.WorkspaceSecurity;
import com.taskflow.api.dto.request.ImportConnectRequest;
import com.taskflow.api.dto.request.ImportUploadRequest;
import com.taskflow.api.dto.request.ValidationPatterns;
import com.taskflow.api.dto.response.ImportJobResponse;
import com.taskflow.api.dto.response.ImportJobResponse.Csv;
import com.taskflow.api.dto.response.ImportJobResponse.Discovery;
import com.taskflow.api.dto.response.ImportJobResponse.FoundProject;
import com.taskflow.api.dto.response.ImportJobResponse.Preview;
import com.taskflow.api.dto.response.ImportJobResponse.Progress;
import com.taskflow.api.dto.response.ImportJobResponse.Report;
import com.taskflow.api.dto.response.ImportJobResponse.Sample;
import com.taskflow.api.dto.response.ImportJobResponse.SkippedGroup;
import com.taskflow.api.entity.ImportJob;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.entity.enums.ImportStatus;
import com.taskflow.api.entity.enums.ImportVia;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.imports.ImportCredentials;
import com.taskflow.api.imports.ImportDefaults;
import com.taskflow.api.imports.ImportDefaults.PersonRow;
import com.taskflow.api.imports.ImportDefaults.Proposal;
import com.taskflow.api.imports.ImportDiscovery;
import com.taskflow.api.imports.ImportJson;
import com.taskflow.api.imports.ImportMapping;
import com.taskflow.api.imports.ImportMapping.PersonAction;
import com.taskflow.api.imports.ImportMapping.PersonTarget;
import com.taskflow.api.imports.ImportMapping.StateTarget;
import com.taskflow.api.imports.ImportPlan;
import com.taskflow.api.imports.ImportPlan.PlannedIssue;
import com.taskflow.api.imports.ImportPlan.PlannedProject;
import com.taskflow.api.imports.ImportProgress;
import com.taskflow.api.imports.ImportReport;
import com.taskflow.api.imports.ImportReport.SkipReason;
import com.taskflow.api.imports.ImportReport.Skipped;
import com.taskflow.api.imports.ImportRunner;
import com.taskflow.api.imports.ImportSelection;
import com.taskflow.api.imports.ImportSelection.ProjectChoice;
import com.taskflow.api.imports.ImportStore;
import com.taskflow.api.imports.csv.CsvField;
import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtSprint;
import com.taskflow.api.imports.source.ImportSource;
import com.taskflow.api.imports.source.SourceConnection;
import com.taskflow.api.imports.source.SourceException;
import com.taskflow.api.imports.source.TrelloFile;
import com.taskflow.api.repository.ImportJobRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.StoredFileRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.security.AccessControl;
import com.taskflow.api.service.ImportService;
import com.taskflow.api.storage.ObjectStorage;
import com.taskflow.api.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    /** Tamaño máximo del archivo que se sube para importar. */
    private static final long MAX_UPLOAD_BYTES = 14L * 1024 * 1024;
    private static final int SAMPLE_SIZE = 5;
    /** Cuántas cosas que no entraron se muestran de cada motivo (todas van en el informe que se descarga). */
    private static final int SKIPPED_SHOWN = 20;
    private static final int LABEL_MAX = 200;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern IDENTIFIER = Pattern.compile(ValidationPatterns.PROJECT_IDENTIFIER);
    private static final List<ImportStatus> WORKING = List.of(ImportStatus.FETCHING, ImportStatus.RUNNING, ImportStatus.UNDOING);

    private static final String NOT_FOUND = "Importación no encontrada";
    private static final String BUSY = "Espera a que termine lo que está haciendo esta importación";

    private final ResourceLookup lookup;
    private final ImportJobRepository jobRepository;
    private final ImportStore store;
    private final ImportRunner runner;
    private final ImportCredentials credentials;
    private final AccessControl accessControl;
    private final WorkspaceSecurity workspaceSecurity;
    private final WorkspaceMemberRepository memberRepository;
    private final ProjectRepository projectRepository;
    private final StateRepository stateRepository;
    private final LabelRepository labelRepository;
    private final StoredFileRepository fileRepository;
    private final StorageProperties storageProperties;
    private final ObjectStorage storage;

    @Override
    @Transactional(readOnly = true)
    public List<ImportJobResponse> list(String slug) {
        Workspace workspace = lookup.workspace(slug);
        return jobRepository.findHistory(workspace.getId(), List.of(ImportStatus.values())).stream()
                .map(job -> view(slug, job, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ImportJobResponse get(String slug, UUID jobId) {
        return view(slug, job(slug, jobId), true);
    }

    @Override
    @Transactional
    public ImportJobResponse connect(String slug, UUID userId, ImportConnectRequest request) {
        Workspace workspace = lookup.workspace(slug);
        ImportSource source = runner.source(request.source());
        if (source == null) {
            throw new BadRequestException("De ese origen solo se importa con un archivo");
        }
        SourceConnection connection = new SourceConnection(request.baseUrl(), request.email(), request.token());
        ImportSource.Discovery discovery;
        try {
            discovery = source.discover(connection);
        } catch (SourceException ex) {
            throw new BadRequestException(ex.getMessage());
        }
        ImportJob job = newJob(workspace, userId, request.source(), ImportVia.API, discovery.label());
        job.setDiscovery(ImportJson.write(new ImportDiscovery(discovery.account(), discovery.projects(), null)));
        jobRepository.save(job);
        credentials.put(job.getId(), connection);
        return view(slug, job, true);
    }

    @Override
    @Transactional
    public ImportJobResponse upload(String slug, UUID userId, ImportUploadRequest request) {
        Workspace workspace = lookup.workspace(slug);
        byte[] file;
        try {
            file = Base64.getDecoder().decode(request.contentBase64().strip());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("El archivo no llegó completo. Vuelve a subirlo.");
        }
        if (file.length == 0) {
            throw new BadRequestException("El archivo está vacío");
        }
        if (file.length > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("El archivo pesa más de 14 MB. Expórtalo por partes (por proyecto, por ejemplo).");
        }
        ImportDiscovery discovery;
        if (request.source() == ImportSourceType.TRELLO) {
            ImportBundle bundle;
            try {
                bundle = TrelloFile.toBundle(file);
            } catch (SourceException ex) {
                throw new BadRequestException(ex.getMessage());
            }
            discovery = new ImportDiscovery(null, found(bundle), null);
        } else {
            CsvImport.Preview preview = CsvImport.preview(file);
            if (preview.columns().isEmpty() || preview.rows() == 0) {
                throw new BadRequestException("El archivo no tiene filas. Debe ser un CSV con una fila de encabezados y una tarea por fila.");
            }
            List<CsvField> suggested = preview.columns().stream().map(CsvImport.Column::suggested).toList();
            discovery = new ImportDiscovery(null, found(CsvImport.toBundle(file, suggested, baseName(request.fileName()))), preview);
        }
        ImportJob job = newJob(workspace, userId, request.source(), ImportVia.FILE, request.fileName().strip());
        job.setDiscovery(ImportJson.write(discovery));
        jobRepository.saveAndFlush(job);
        store.savePayload(job.getId(), ImportStore.UPLOAD, file);
        return view(slug, job, true);
    }

    private static List<ImportSource.Found> found(ImportBundle bundle) {
        return bundle.projects().stream()
                .map(project -> new ImportSource.Found(project.id(), project.key(), project.name(), project.issues().size())).toList();
    }

    private static String baseName(String fileName) {
        return fileName.strip().replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
    }

    private ImportJob newJob(Workspace workspace, UUID userId, ImportSourceType source, ImportVia via, String label) {
        ImportJob job = new ImportJob();
        job.setWorkspace(workspace);
        job.setCreatedBy(lookup.user(userId));
        job.setSource(source);
        job.setVia(via);
        job.setSourceLabel(label == null || label.isBlank() ? source.name() : label.length() > LABEL_MAX ? label.substring(0, LABEL_MAX) : label);
        job.setStatus(ImportStatus.DRAFT);
        return job;
    }

    @Override
    @Transactional
    public ImportJobResponse select(String slug, UUID jobId, UUID userId, ImportSelection selection) {
        ImportJob job = lockedJob(slug, jobId);
        if (job.getStatus() != ImportStatus.DRAFT && job.getStatus() != ImportStatus.READY) {
            throw new ConflictException(job.getStatus().working() ? BUSY : "Esta importación ya terminó: empieza una nueva");
        }
        ImportDiscovery discovery = ImportJson.read(job.getDiscovery(), ImportDiscovery.class);
        validate(slug, job, discovery, selection);
        ensureMayTarget(slug, userId, selection);
        if (job.getVia() == ImportVia.API && credentials.get(jobId).isEmpty()) {
            throw new ConflictException(ImportRunner.CONNECTION_LOST);
        }
        job.setSelection(ImportJson.write(selection));
        job.setMapping(null);
        job.setStatus(ImportStatus.FETCHING);
        job.setError(null);
        job.setProgress(null);
        afterCommit(() -> runner.fetch(jobId));
        return view(slug, job, true);
    }

    /**
     * Quien importa solo escribe donde podría hacerlo a mano. En un proyecto existente, tiene que poder verlo y poder
     * agregarle personas (la importación crea su contenido y suma como miembros a quienes quedan asignados). Para un
     * proyecto nuevo, tiene que poder crear proyectos. Sin esto, alguien con «Importar datos» escribía en proyectos que
     * no ve y se hacía miembro de ellos.
     */
    private void ensureMayTarget(String slug, UUID userId, ImportSelection selection) {
        List<ProjectChoice> choices = selection == null || selection.projects() == null ? List.of() : selection.projects();
        for (ProjectChoice choice : choices) {
            if (choice.targetProjectId() == null) {
                if (!accessControl.can(slug, userId, Permission.PROJECT_CREATE)) {
                    throw new ForbiddenException("Tu rol no permite crear proyectos: elige uno existente como destino");
                }
            } else if (!workspaceSecurity.canAccessProject(userId, slug, choice.targetProjectId())) {
                // Igual que si no existiera: no se revela qué proyectos hay
                throw new BadRequestException("El proyecto de destino no existe en este workspace");
            } else if (!accessControl.can(slug, userId, Permission.PROJECT_MEMBERS)) {
                throw new ForbiddenException("Para importar en un proyecto existente necesitas poder agregarle personas");
            }
        }
    }

    private void validate(String slug, ImportJob job, ImportDiscovery discovery, ImportSelection selection) {
        boolean csv = discovery.csv() != null;
        List<ProjectChoice> choices = selection.projects() == null ? List.of() : selection.projects();
        if (choices.isEmpty() && !csv) {
            throw new BadRequestException("Elige al menos un proyecto");
        }
        Set<String> known = new HashSet<>();
        discovery.projects().forEach(project -> known.add(project.id()));
        Set<String> seen = new HashSet<>();
        for (ProjectChoice choice : choices) {
            if (choice.externalId() == null || !seen.add(choice.externalId()) || (!csv && !known.contains(choice.externalId()))) {
                throw new BadRequestException("Uno de los proyectos elegidos no está en el origen");
            }
            if (choice.targetProjectId() != null) {
                if (projectRepository.findInWorkspace(choice.targetProjectId(), slug).isEmpty()) {
                    throw new BadRequestException("El proyecto de destino no existe en este workspace");
                }
                continue;
            }
            if (choice.name() != null && choice.name().strip().length() > 80) {
                throw new BadRequestException("El nombre no puede superar 80 caracteres");
            }
            if (choice.identifier() != null && !choice.identifier().isBlank() && !IDENTIFIER.matcher(choice.identifier().strip()).matches()) {
                throw new BadRequestException("El identificador debe empezar por letra y tener hasta 12 letras o números");
            }
        }
        if (csv) {
            List<CsvField> columns = selection.csv() == null ? null : selection.csv().columns();
            if (columns == null || columns.size() > discovery.csv().columns().size() || !columns.contains(CsvField.TITLE)) {
                throw new BadRequestException("Elige qué columna del archivo es el título de la tarea");
            }
        }
    }

    @Override
    @Transactional
    public ImportJobResponse saveMapping(String slug, UUID jobId, UUID userId, ImportMapping mapping) {
        ImportJob job = job(slug, jobId);
        requireReady(job);
        Workspace workspace = job.getWorkspace();
        if (mapping.states() != null) {
            for (StateTarget state : mapping.states().values()) {
                if (state == null || state.group() == null || state.name() == null || state.name().isBlank() || state.name().strip().length() > 50) {
                    throw new BadRequestException("Cada estado necesita un nombre de hasta 50 caracteres y un grupo");
                }
            }
        }
        if (mapping.people() != null) {
            Set<UUID> members = new HashSet<>();
            memberRepository.findAllByWorkspaceIdWithUser(workspace.getId()).forEach(member -> members.add(member.getUser().getId()));
            boolean mayInvite = accessControl.can(slug, userId, Permission.MEMBER_INVITE);
            for (PersonTarget person : mapping.people().values()) {
                if (person == null || person.action() == null) {
                    throw new BadRequestException("Falta decir qué pasa con una de las personas");
                }
                if (person.action() == PersonAction.MEMBER && !members.contains(person.userId())) {
                    throw new BadRequestException("Una de las personas elegidas ya no es miembro del workspace");
                }
                if (person.action() == PersonAction.INVITE) {
                    if (!mayInvite) {
                        throw new ForbiddenException("Tu rol no permite invitar personas");
                    }
                    if (person.email() == null || !EMAIL.matcher(person.email().strip()).matches()) {
                        throw new BadRequestException("Para invitar a una persona hace falta su correo");
                    }
                }
            }
        }
        job.setMapping(ImportJson.write(mapping));
        return view(slug, job, true);
    }

    @Override
    @Transactional
    public ImportJobResponse start(String slug, UUID jobId, UUID userId) {
        ImportJob job = lockedJob(slug, jobId);
        requireReady(job);
        boolean another = jobRepository.findAllByStatusIn(WORKING).stream()
                .anyMatch(other -> other.getWorkspace().getId().equals(job.getWorkspace().getId()) && !other.getId().equals(jobId));
        if (another) {
            throw new ConflictException("Ya hay otra importación en curso en este workspace. Espera a que termine.");
        }
        // La selección pudo guardarla otra persona (o cambiar los permisos desde entonces): se comprueba con quien arranca
        ensureMayTarget(slug, userId, ImportJson.read(job.getSelection(), ImportSelection.class));
        // Las equivalencias con las que corre: lo que la persona eligió y, donde no tocó nada, lo propuesto
        Ready ready = ready(slug, job);
        ImportMapping mapping = ready.proposal().toMapping();
        if (!accessControl.can(slug, userId, Permission.MEMBER_INVITE)) {
            // Las equivalencias pueden venir de la importación anterior, hecha por alguien que sí podía invitar
            mapping = withoutInvitations(mapping);
        }
        job.setMapping(ImportJson.write(mapping));
        job.setStatus(ImportStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setError(null);
        Language language = Language.current();
        afterCommit(() -> runner.run(jobId, slug, language));
        return view(slug, job, false);
    }

    /** Las mismas equivalencias con las personas «invitar» puestas en «sin asignar». */
    private static ImportMapping withoutInvitations(ImportMapping mapping) {
        if (mapping.people() == null) {
            return mapping;
        }
        Map<String, PersonTarget> people = new LinkedHashMap<>(mapping.people());
        people.replaceAll((id, target) -> target != null && target.action() == PersonAction.INVITE
                ? new PersonTarget(PersonAction.UNASSIGNED, null, target.email(), null) : target);
        return new ImportMapping(mapping.types(), mapping.states(), mapping.priorities(), people);
    }

    private static void requireReady(ImportJob job) {
        if (job.getStatus() != ImportStatus.READY) {
            throw new ConflictException(job.getStatus().working() ? BUSY : "Primero elige qué traer");
        }
    }

    @Override
    @Transactional
    public ImportJobResponse stop(String slug, UUID jobId) {
        ImportJob job = job(slug, jobId);
        if (job.getStatus() != ImportStatus.RUNNING) {
            throw new ConflictException("Esta importación no está corriendo");
        }
        runner.requestStop(jobId);
        return view(slug, job, true);
    }

    @Override
    @Transactional
    public ImportJobResponse undo(String slug, UUID jobId) {
        ImportJob job = lockedJob(slug, jobId);
        if (!job.getStatus().finished()) {
            throw new ConflictException(job.getStatus().working() ? BUSY : "Esta importación no tiene nada que deshacer");
        }
        ImportStatus previous = job.getStatus();
        job.setStatus(ImportStatus.UNDOING);
        job.setError(null);
        afterCommit(() -> runner.undo(jobId, previous));
        return view(slug, job, false);
    }

    @Override
    @Transactional
    public void discard(String slug, UUID jobId) {
        ImportJob job = job(slug, jobId);
        if (job.getStatus() != ImportStatus.DRAFT && job.getStatus() != ImportStatus.READY && job.getStatus() != ImportStatus.UNDONE) {
            throw new ConflictException(job.getStatus().working() ? BUSY : "Una importación que ya trajo datos no se descarta: se deshace");
        }
        credentials.remove(jobId);
        jobRepository.delete(job);
    }

    @Override
    @Transactional(readOnly = true)
    public FileDownload report(String slug, UUID jobId) {
        ImportJob job = job(slug, jobId);
        ImportReport report = ImportJson.read(job.getReport(), ImportReport.class);
        if (report == null) {
            throw new ConflictException("Esta importación todavía no tiene informe");
        }
        Language language = Language.current();
        StringBuilder csv = new StringBuilder("﻿");
        row(csv, language.t("Motivo"), language.t("Código"), language.t("Tarea"), language.t("Detalle"), language.t("Enlace"));
        for (Skipped skipped : report.skipped) {
            row(csv, language.t(reason(skipped.reason())), skipped.key(), skipped.title(), skipped.detail(), skipped.url());
        }
        return new FileDownload("importacion-" + job.getSource().name().toLowerCase() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(SkipReason reason) {
        return switch (reason) {
            case ATTACHMENT_TOO_BIG -> "Adjunto que pasa del límite por archivo";
            case ATTACHMENT_TYPE -> "Adjunto de un tipo que TaskFlow no acepta";
            case ATTACHMENT_QUOTA -> "Adjunto que no cabe en el espacio del workspace";
            case ATTACHMENT_FAILED -> "Adjunto que no se pudo descargar";
            case LINK_OUTSIDE -> "Relación con una tarea que no se importó";
            case INVITATION_FAILED -> "Invitación que no se pudo enviar";
            case ISSUE_FAILED -> "Tarea que no se pudo crear";
        };
    }

    private static void row(StringBuilder csv, String... values) {
        for (int index = 0; index < values.length; index++) {
            String value = values[index] == null ? "" : values[index];
            if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
                value = "'" + value;
            }
            csv.append(index == 0 ? "" : ",").append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    // ----- Lo que se responde

    private ImportJob job(String slug, UUID jobId) {
        return jobRepository.findInWorkspace(jobId, slug).orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    /** La importación, bloqueada mientras cambia de paso (ver {@link ImportJobRepository#findInWorkspaceForUpdate}). */
    private ImportJob lockedJob(String slug, UUID jobId) {
        return jobRepository.findInWorkspaceForUpdate(jobId, slug).orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private ImportJobResponse view(String slug, ImportJob job, boolean full) {
        ImportDiscovery stored = full ? ImportJson.read(job.getDiscovery(), ImportDiscovery.class) : null;
        Discovery discovery = stored == null ? null : discovery(job.getWorkspace().getId(), stored);
        Ready ready = full && job.getStatus() == ImportStatus.READY ? ready(slug, job) : null;
        ImportProgress progress = ImportJson.read(job.getProgress(), ImportProgress.class);
        ImportReport report = ImportJson.read(job.getReport(), ImportReport.class);
        return new ImportJobResponse(job.getId(), job.getSource(), job.getVia(), job.getSourceLabel(), job.getStatus(),
                job.getError() == null ? null : Language.current().t(job.getError()),
                job.getCreatedBy() == null ? null : job.getCreatedBy().getFullName(), job.getCreatedAt(), job.getStartedAt(),
                job.getFinishedAt(), credentials.get(job.getId()).isPresent(), discovery,
                full ? ImportJson.read(job.getSelection(), ImportSelection.class) : null,
                ready == null ? null : ready.proposal(), ready == null ? null : ready.preview(),
                progress == null ? null : new Progress(progress.percent(), progress.stages(), progress.current()),
                report == null ? null : report(report));
    }

    private Discovery discovery(UUID workspaceId, ImportDiscovery stored) {
        Set<String> suggested = new HashSet<>();
        List<FoundProject> projects = new ArrayList<>();
        for (ImportSource.Found found : stored.projects()) {
            String wanted = found.key() != null && !found.key().isBlank() ? found.key() : found.name();
            // Libre en el workspace y distinto de los que ya se propusieron en esta misma lista
            String identifier = ImportDefaults.identifier(wanted,
                    candidate -> suggested.contains(candidate) || projectRepository.existsByWorkspaceIdAndIdentifier(workspaceId, candidate));
            suggested.add(identifier);
            boolean taken = found.key() != null && !found.key().isBlank()
                    && projectRepository.existsByWorkspaceIdAndIdentifier(workspaceId, found.key().toUpperCase());
            projects.add(new FoundProject(found.id(), found.key(), found.name(), found.issueCount(), identifier, taken));
        }
        Csv csv = stored.csv() == null ? null
                : new Csv(String.valueOf(stored.csv().delimiter()), stored.csv().rows(), stored.csv().columns());
        return new Discovery(stored.account(), projects, csv);
    }

    private record Ready(Proposal proposal, Preview preview) {
    }

    /** Con lo elegido ya leído: las equivalencias que se proponen y lo que se crearía con ellas. */
    private Ready ready(String slug, ImportJob job) {
        UUID workspaceId = job.getWorkspace().getId();
        ImportBundle bundle = ImportJson.decompress(store.payload(job.getId(), ImportStore.BUNDLE), ImportBundle.class);
        ImportSelection selection = ImportJson.read(job.getSelection(), ImportSelection.class);
        ImportMapping chosen = ImportJson.read(job.getMapping(), ImportMapping.class);
        ImportMapping previous = jobRepository
                .findFirstByWorkspaceIdAndSourceAndStatusOrderByCreatedAtDesc(workspaceId, job.getSource(), ImportStatus.COMPLETED)
                .map(last -> ImportJson.read(last.getMapping(), ImportMapping.class)).orElse(null);
        List<User> members = memberRepository.findAllByWorkspaceIdWithUser(workspaceId).stream().map(WorkspaceMember::getUser).toList();

        // Los estados entre los que se elige: los de fábrica si se crea algún proyecto y los de los que ya existen
        Map<String, StateTarget> choices = new LinkedHashMap<>();
        Map<UUID, List<State>> existingStates = new HashMap<>();
        List<ProjectChoice> projects = selection.projects() == null ? List.of() : selection.projects();
        if (projects.stream().anyMatch(ProjectChoice::createsProject)) {
            ImportDefaults.FACTORY_STATES.forEach(state -> choices.put(key(state.name()), state));
        }
        for (ProjectChoice choice : projects) {
            if (!choice.createsProject()) {
                List<State> states = stateRepository.findAllByProjectIdOrderByPositionAsc(choice.targetProjectId());
                existingStates.put(choice.targetProjectId(), states);
                states.forEach(state -> choices.putIfAbsent(key(state.getName()), new StateTarget(state.getName(), state.getStateGroup())));
            }
        }
        Proposal proposal = ImportDefaults.propose(bundle, selection, chosen, previous, members, List.copyOf(choices.values()));
        ImportMapping mapping = proposal.toMapping();
        ImportPlan plan = ImportPlan.of(bundle, selection, mapping);

        Map<String, StateTarget> newStates = new LinkedHashMap<>();
        int labels = 0;
        for (PlannedProject project : plan.projects()) {
            Set<String> stateNames = new HashSet<>();
            Set<String> labelNames = new HashSet<>();
            if (project.choice().createsProject()) {
                ImportDefaults.FACTORY_STATES.forEach(state -> stateNames.add(key(state.name())));
            } else {
                existingStates.get(project.choice().targetProjectId()).forEach(state -> stateNames.add(key(state.getName())));
                for (Label label : labelRepository.findAllByProjectIdOrderByNameAsc(project.choice().targetProjectId())) {
                    labelNames.add(key(label.getName()));
                }
            }
            for (PlannedIssue issue : project.issues()) {
                StateTarget target = mapping.states().get(issue.source().status());
                if (target != null && stateNames.add(key(target.name()))) {
                    newStates.putIfAbsent(key(target.name()), target);
                }
                if (issue.source().labels() != null) {
                    for (String label : issue.source().labels()) {
                        labels += !key(label).isEmpty() && labelNames.add(key(label)) ? 1 : 0;
                    }
                }
            }
        }
        long free = Math.max(0, storageProperties.workspaceQuotaBytes() - fileRepository.sumReservedBytes(workspaceId));
        boolean attachments = storage.enabled() && job.getVia() == ImportVia.API && credentials.get(job.getId()).isPresent();
        Preview preview = new Preview(plan.totals(), labels, List.copyOf(newStates.values()), sample(plan, proposal), free, attachments);
        return new Ready(proposal, preview);
    }

    private static String key(String name) {
        return CsvField.normalize(name);
    }

    /** Unas pocas tareas del primer proyecto, variadas (una historia, una tarea con padre, un bug, una terminada…), como quedarían. */
    private static List<Sample> sample(ImportPlan plan, Proposal proposal) {
        if (plan.projects().isEmpty()) {
            return List.of();
        }
        PlannedProject project = plan.projects().get(0);
        Map<String, PlannedIssue> byId = new HashMap<>();
        project.issues().forEach(issue -> byId.put(issue.source().id(), issue));
        Map<String, ExtSprint> sprints = new HashMap<>();
        project.sprints().forEach(sprint -> sprints.put(sprint.id(), sprint));
        Map<String, PersonRow> people = new HashMap<>();
        proposal.people().forEach(person -> people.put(person.id(), person));

        List<PlannedIssue> picked = new ArrayList<>();
        pick(picked, project, issue -> issue.type() == IssueType.STORY);
        pick(picked, project, issue -> issue.type() == IssueType.TASK && issue.parentId() != null);
        pick(picked, project, issue -> issue.type() == IssueType.BUG);
        pick(picked, project, issue -> issue.source().completedAt() != null);
        pick(picked, project, issue -> issue.source().assigneeIds() != null && issue.source().assigneeIds().stream()
                .anyMatch(id -> plan.person(id).action() == PersonAction.INVITE));
        for (PlannedIssue issue : project.issues()) {
            if (picked.size() >= SAMPLE_SIZE) {
                break;
            }
            if (!picked.contains(issue)) {
                picked.add(issue);
            }
        }

        String identifier = identifier(project);
        boolean numbered = project.choice().createsProject() && plan.options().keepNumbers();
        List<Sample> sample = new ArrayList<>();
        for (PlannedIssue issue : picked) {
            ExtIssue source = issue.source();
            StateTarget state = plan.mapping().states().get(source.status());
            List<String> assignees = new ArrayList<>();
            List<String> invited = new ArrayList<>();
            if (source.assigneeIds() != null) {
                for (String id : source.assigneeIds()) {
                    PersonRow person = people.get(id);
                    PersonTarget target = plan.person(id);
                    if (target.action() == PersonAction.MEMBER && person != null && person.matchedName() != null) {
                        assignees.add(person.matchedName());
                    } else if (target.action() == PersonAction.INVITE && person != null) {
                        invited.add(person.name());
                    }
                }
            }
            PlannedIssue parent = issue.parentId() == null ? null : byId.get(issue.parentId());
            String module = issue.moduleKeys().isEmpty() ? null : project.modules().get(issue.moduleKeys().get(0)).name();
            ExtSprint sprint = issue.sprintId() == null ? null : sprints.get(issue.sprintId());
            sample.add(new Sample(issue.type(), code(identifier, numbered, source), source.title(),
                    state == null ? source.status() : state.name(), state == null ? null : state.group(),
                    parent == null ? null : code(identifier, numbered, parent.source()), module, sprint == null ? null : sprint.name(),
                    assignees, invited, source.completedAt()));
        }
        return sample;
    }

    private static void pick(List<PlannedIssue> picked, PlannedProject project, java.util.function.Predicate<PlannedIssue> wanted) {
        for (PlannedIssue issue : project.issues()) {
            if (wanted.test(issue) && !picked.contains(issue)) {
                if (picked.size() < SAMPLE_SIZE) {
                    picked.add(issue);
                }
                return;
            }
        }
    }

    private static String identifier(PlannedProject project) {
        ProjectChoice choice = project.choice();
        String wanted = choice.identifier() != null && !choice.identifier().isBlank() ? choice.identifier()
                : project.source().key() != null ? project.source().key() : project.source().name();
        return ImportDefaults.identifier(wanted == null ? "" : wanted, candidate -> false);
    }

    private static String code(String identifier, boolean numbered, ExtIssue issue) {
        return numbered && issue.number() != null ? identifier + "-" + issue.number() : null;
    }

    private static Report report(ImportReport report) {
        Map<SkipReason, List<Skipped>> groups = new LinkedHashMap<>();
        for (Skipped skipped : report.skipped) {
            groups.computeIfAbsent(skipped.reason(), reason -> new ArrayList<>()).add(skipped);
        }
        List<SkippedGroup> skipped = new ArrayList<>();
        groups.forEach((reason, items) -> skipped.add(new SkippedGroup(reason, items.size(),
                items.subList(0, Math.min(items.size(), SKIPPED_SHOWN)))));
        return new Report(report.stories, report.tasks, report.bugs, report.modules, report.cycles, report.comments,
                report.attachments, report.attachmentBytes, report.labels, report.states, report.relations, report.invitations,
                report.projects, report.skippedTotal, skipped);
    }

    /** Lo que tarda corre en otro hilo, y solo cuando lo guardado aquí ya está en la base. */
    private static void afterCommit(Runnable task) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
