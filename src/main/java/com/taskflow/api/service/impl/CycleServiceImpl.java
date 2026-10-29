package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.CloseCycleRequest;
import com.taskflow.api.dto.request.CycleMoveTarget;
import com.taskflow.api.dto.request.CycleRequest;
import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.UpdateCycleRequest;
import com.taskflow.api.dto.response.CloseCyclePreviewResponse;
import com.taskflow.api.dto.response.CloseCycleResponse;
import com.taskflow.api.dto.response.CycleResponse;
import com.taskflow.api.dto.response.CycleStatus;
import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.dto.response.RelatedIssueResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.mapper.PlanningMapper;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.CycleService;
import com.taskflow.api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CycleServiceImpl implements CycleService {

    private static final String INVALID_DATES = "La fecha de fin no puede ser anterior a la de inicio";

    static final String CLOSED_EDIT_MESSAGE = "El ciclo está cerrado: ya no se pueden cambiar su nombre ni sus fechas";
    static final String CLOSED_ADD_MESSAGE = "El ciclo está cerrado: ya no se le pueden agregar tareas";
    static final String CLOSED_REMOVE_MESSAGE = "El ciclo está cerrado: ya no se le pueden quitar tareas";
    static final String ALREADY_CLOSED_MESSAGE = "El ciclo ya está cerrado";
    static final String NOT_STARTED_MESSAGE = "El ciclo todavía no ha empezado: no se puede cerrar";
    static final String NO_NEXT_CYCLE_MESSAGE =
            "No hay un ciclo siguiente sin cerrar: elige un ciclo destino o mueve las tareas al backlog";

    /** Las tareas del cierre se listan por número. */
    private static final Comparator<Issue> BY_NUMBER = Comparator.comparingInt(Issue::getSequenceId);

    private final CycleRepository cycleRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final PlanningSupport support;
    private final ResourceLookup lookup;
    private final PlanningMapper mapper;
    private final IssueMapper issueMapper;
    private final AppProperties properties;

    @Override
    @Transactional(readOnly = true)
    public List<CycleResponse> getCycles(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        List<Cycle> cycles = cycleRepository.findAllByProjectId(project.getId());
        if (cycles.isEmpty()) {
            return List.of();
        }
        Map<UUID, ProgressResponse> progress = PlanningSupport.progressById(
                cycleRepository.findProgress(cycles.stream().map(Cycle::getId).toList(), StateGroup.COMPLETED));
        return cycles.stream()
                .map(cycle -> toResponse(cycle, progress.getOrDefault(cycle.getId(), ProgressResponse.of(0, 0))))
                .toList();
    }

    @Override
    @Transactional
    public CycleResponse create(String slug, UUID projectId, CycleRequest request) {
        Project project = lookup.project(slug, projectId);
        PlanningSupport.validateDates(request.startDate(), request.endDate(), INVALID_DATES);

        Cycle cycle = new Cycle();
        cycle.setProject(project);
        cycle.setName(request.name().trim());
        cycle.setDescription(PlanningSupport.blankToNull(request.description()));
        cycle.setStartDate(request.startDate());
        cycle.setEndDate(request.endDate());
        cycleRepository.save(cycle);
        return toResponse(cycle, ProgressResponse.of(0, 0));
    }

    @Override
    @Transactional(readOnly = true)
    public CycleResponse get(String slug, UUID projectId, UUID cycleId) {
        return toResponse(findCycle(slug, projectId, cycleId));
    }

    @Override
    @Transactional
    public CycleResponse update(String slug, UUID projectId, UUID cycleId, UpdateCycleRequest request) {
        Cycle cycle = findCycle(slug, projectId, cycleId);
        if (cycle.isClosed() && changesNameOrDates(cycle, request)) {
            throw new ConflictException(CLOSED_EDIT_MESSAGE);
        }
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            cycle.setName(request.name().trim());
        }
        if (request.description() != null) {
            cycle.setDescription(PlanningSupport.blankToNull(request.description()));
        }
        LocalDate startDate = PlanningSupport.editedDate(cycle.getStartDate(), request.startDate(),
                request.clearDates(), request.clearStartDate());
        LocalDate endDate = PlanningSupport.editedDate(cycle.getEndDate(), request.endDate(),
                request.clearDates(), request.clearEndDate());
        PlanningSupport.validateDates(startDate, endDate, INVALID_DATES);
        cycle.setStartDate(startDate);
        cycle.setEndDate(endDate);
        return toResponse(cycleRepository.saveAndFlush(cycle));
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID cycleId) {
        // Los issues no se borran: solo se quitan del ciclo (ON DELETE CASCADE en cycle_issues).
        cycleRepository.delete(findCycle(slug, projectId, cycleId));
    }

    @Override
    @Transactional
    public CycleResponse addIssues(String slug, UUID projectId, UUID cycleId, UUID userId, IssueIdsRequest request) {
        // Con la fila del ciclo bloqueada: no se agregan tareas mientras el project manager lo cierra
        Cycle cycle = findCycleForUpdate(lookup.project(slug, projectId), cycleId);
        if (cycle.isClosed()) {
            throw new ConflictException(CLOSED_ADD_MESSAGE);
        }
        List<Issue> issues = support.findIssues(cycle.getProject(), request.issueIds());
        User actor = lookup.user(userId);
        // El ciclo del que sale cada tarea (también uno cerrado: su resumen quedó guardado al cerrarlo), para el
        // historial («de Sprint 1 a Sprint 2»)
        Map<UUID, String> previousCycle = new HashMap<>();
        for (Issue issue : issues) {
            issue.getCycles().stream()
                    .filter(other -> !other.getId().equals(cycle.getId()))
                    .findFirst()
                    .ifPresent(other -> previousCycle.put(issue.getId(), other.getName()));
        }

        // Un issue solo puede estar en un ciclo: se saca de cualquier otro antes de agregarlo.
        cycleRepository.removeIssuesFromOtherCycles(cycle.getId(), issues.stream().map(Issue::getId).toList());
        for (Issue issue : issues) {
            if (cycle.getIssues().add(issue)) {
                activityService.record(issue, actor, ActivityField.CYCLE, previousCycle.get(issue.getId()),
                        cycle.getName());
            }
        }
        return toResponse(cycleRepository.saveAndFlush(cycle));
    }

    @Override
    @Transactional
    public CycleResponse removeIssue(String slug, UUID projectId, UUID cycleId, UUID issueId, UUID userId) {
        Cycle cycle = findCycleForUpdate(lookup.project(slug, projectId), cycleId);
        if (cycle.isClosed()) {
            throw new ConflictException(CLOSED_REMOVE_MESSAGE);
        }
        Issue issue = cycle.getIssues().stream()
                .filter(candidate -> candidate.getId().equals(issueId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("La tarea no está en este ciclo"));
        cycle.getIssues().remove(issue);
        activityService.record(issue, lookup.user(userId), ActivityField.CYCLE, cycle.getName(), null);
        return toResponse(cycleRepository.saveAndFlush(cycle));
    }

    @Override
    @Transactional(readOnly = true)
    public CloseCyclePreviewResponse closePreview(String slug, UUID projectId, UUID cycleId) {
        Project project = lookup.project(slug, projectId);
        Cycle cycle = findCycle(project, cycleId);
        // Cuenta el trabajo (tareas y bugs), como el avance del ciclo: las historias no
        List<Issue> issues = cycle.getIssues().stream().filter(PlanningSupport::isWork).sorted(BY_NUMBER).toList();
        List<Issue> completed = issues.stream().filter(PlanningSupport::isCompleted).toList();
        List<Issue> unfinished = issues.stream().filter(PlanningSupport::isOpen).toList();
        Cycle next = nextCycle(cycle, cycleRepository.findAllByProjectId(project.getId()), today());
        return new CloseCyclePreviewResponse(
                toResponse(cycle),
                toRefs(completed),
                toRefs(unfinished),
                completed.size(),
                unfinished.size(),
                PlanningSupport.cycleTimeDays(completed),
                next == null ? null : toResponse(next));
    }

    @Override
    @Transactional
    public CloseCycleResponse close(String slug, UUID projectId, UUID cycleId, UUID userId, CloseCycleRequest request) {
        Project project = lookup.project(slug, projectId);
        // Con la fila bloqueada: dos cierres a la vez (un doble clic) se ejecutan en fila y el segundo recibe 409, y
        // nadie agrega una tarea al ciclo mientras se cierra
        Cycle cycle = findCycleForUpdate(project, cycleId);
        if (cycle.isClosed()) {
            throw new ConflictException(ALREADY_CLOSED_MESSAGE);
        }
        LocalDate today = today();
        if (cycle.getStartDate() != null && cycle.getStartDate().isAfter(today)) {
            // Además, acortar su fin a hoy lo dejaría terminando antes de empezar.
            throw new ConflictException(NOT_STARTED_MESSAGE);
        }

        // Destino de cada tarea sin terminar: su excepción o, si no tiene, el general.
        Map<UUID, CycleMoveTarget> overrides = new HashMap<>();
        if (request.overrides() != null) {
            request.overrides().forEach(override -> overrides.put(override.issueId(), override.moveTo()));
        }
        List<Issue> toCycle = new ArrayList<>();
        List<Issue> toBacklog = new ArrayList<>();
        cycle.getIssues().stream()
                .filter(PlanningSupport::isOpen)
                .sorted(BY_NUMBER)
                .forEach(issue -> {
                    CycleMoveTarget target = overrides.getOrDefault(issue.getId(), request.moveTo());
                    (target == CycleMoveTarget.NEXT_CYCLE ? toCycle : toBacklog).add(issue);
                });
        Cycle target = resolveTarget(project, cycle, request.targetCycleId(), !toCycle.isEmpty(), today);
        User actor = lookup.user(userId);

        // Primero se escriben las salidas: una tarea no puede estar en dos ciclos ni un instante (cycle_issues).
        toCycle.forEach(cycle.getIssues()::remove);
        toBacklog.forEach(cycle.getIssues()::remove);
        cycleRepository.saveAndFlush(cycle);
        if (!toCycle.isEmpty()) {
            target.getIssues().addAll(toCycle);
            cycleRepository.saveAndFlush(target);
        }
        for (Issue issue : toCycle) {
            activityService.record(issue, actor, ActivityField.CYCLE, cycle.getName(), target.getName());
        }
        for (Issue issue : toBacklog) {
            activityService.record(issue, actor, ActivityField.CYCLE, cycle.getName(), null);
        }

        if (cycle.getEndDate() == null ? cycle.getStartDate() != null : cycle.getEndDate().isAfter(today)) {
            cycle.setEndDate(today);
        }
        Instant now = Instant.now();
        cycle.setClosedAt(now);
        cycle.setClosedBy(actor);
        // Las cuentas del cierre son del trabajo (tareas y bugs); las historias abiertas se mueven igual, pero no cuentan
        int carriedToCycle = (int) toCycle.stream().filter(PlanningSupport::isWork).count();
        int carriedToBacklog = (int) toBacklog.stream().filter(PlanningSupport::isWork).count();
        cycle.setCarriedToCycle(carriedToCycle);
        cycle.setCarriedToBacklog(carriedToBacklog);
        // Foto del cierre para la retrospectiva: no cambia aunque después una tarea se reabra o cambie de ciclo
        List<Issue> completed = cycle.getIssues().stream()
                .filter(PlanningSupport::isWork)
                .filter(PlanningSupport::isCompleted)
                .toList();
        cycle.setClosedCompletedCount(completed.size());
        cycle.setClosedTotalCount(completed.size() + carriedToCycle + carriedToBacklog);
        cycle.setClosedCycleTimeDays(PlanningSupport.cycleTimeDays(completed));
        boolean retroStarts = Boolean.TRUE.equals(request.startRetro()) && cycle.getRetroStartedAt() == null;
        if (retroStarts) {
            cycle.setRetroStartedAt(now);
        }
        Cycle saved = cycleRepository.saveAndFlush(cycle);
        if (retroStarts) {
            notificationService.notifyRetroStarted(saved, actor);
        }
        return new CloseCycleResponse(toResponse(saved), carriedToCycle, carriedToBacklog,
                saved.getRetroStartedAt() != null);
    }

    /**
     * Siguiente ciclo sin cerrar, el destino por defecto al cerrar uno: el que empieza primero después del inicio de
     * este. Para un ciclo sin fechas, el primero (por inicio) de los que no han terminado. Los borradores no cuentan.
     */
    static Cycle nextCycle(Cycle cycle, List<Cycle> cycles, LocalDate today) {
        return cycles.stream()
                .filter(other -> !other.getId().equals(cycle.getId()) && !other.isClosed()
                        && other.getStartDate() != null)
                .filter(other -> cycle.getStartDate() != null
                        ? other.getStartDate().isAfter(cycle.getStartDate())
                        : other.getEndDate() == null || !other.getEndDate().isBefore(today))
                .min(Comparator.comparing(Cycle::getStartDate)
                        .thenComparing(Cycle::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** Si la edición cambia el nombre o alguna fecha (lo que un ciclo cerrado ya no permite). */
    static boolean changesNameOrDates(Cycle cycle, UpdateCycleRequest request) {
        if (request.name() != null && !request.name().trim().equals(cycle.getName())) {
            return true;
        }
        LocalDate startDate = PlanningSupport.editedDate(cycle.getStartDate(), request.startDate(),
                request.clearDates(), request.clearStartDate());
        LocalDate endDate = PlanningSupport.editedDate(cycle.getEndDate(), request.endDate(),
                request.clearDates(), request.clearEndDate());
        return !Objects.equals(startDate, cycle.getStartDate()) || !Objects.equals(endDate, cycle.getEndDate());
    }

    /**
     * Ciclo al que van las tareas con NEXT_CYCLE: el indicado o, si no se indica, el siguiente. Un destino indicado se
     * valida siempre; sin destino, solo falta el siguiente si alguna tarea lo necesita.
     */
    private Cycle resolveTarget(Project project, Cycle cycle, UUID targetCycleId, boolean needed, LocalDate today) {
        if (targetCycleId != null) {
            if (targetCycleId.equals(cycle.getId())) {
                throw new BadRequestException("El ciclo destino debe ser otro ciclo");
            }
            Cycle target = cycleRepository.findByIdAndProjectId(targetCycleId, project.getId())
                    .orElseThrow(() -> new BadRequestException("El ciclo destino no existe en este proyecto"));
            if (target.isClosed()) {
                throw new ConflictException("El ciclo destino está cerrado");
            }
            return target;
        }
        if (!needed) {
            return null;
        }
        Cycle next = nextCycle(cycle, cycleRepository.findAllByProjectId(project.getId()), today);
        if (next == null) {
            throw new BadRequestException(NO_NEXT_CYCLE_MESSAGE);
        }
        return next;
    }

    private List<RelatedIssueResponse> toRefs(List<Issue> issues) {
        return issues.stream().map(issueMapper::toRelated).toList();
    }

    private Cycle findCycle(String slug, UUID projectId, UUID cycleId) {
        return findCycle(lookup.project(slug, projectId), cycleId);
    }

    private Cycle findCycle(Project project, UUID cycleId) {
        return cycleRepository.findByIdAndProjectId(cycleId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ciclo no encontrado"));
    }

    /** El ciclo con su fila bloqueada hasta el final de la transacción. */
    private Cycle findCycleForUpdate(Project project, UUID cycleId) {
        return cycleRepository.findForUpdate(cycleId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ciclo no encontrado"));
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(properties.timezone()));
    }

    private CycleResponse toResponse(Cycle cycle) {
        return toResponse(cycle, PlanningSupport.progressOf(cycle.getId(),
                ids -> cycleRepository.findProgress(ids, StateGroup.COMPLETED)));
    }

    private CycleResponse toResponse(Cycle cycle, ProgressResponse progress) {
        CycleStatus status = CycleStatus.of(cycle.getStartDate(), cycle.getEndDate(), cycle.isClosed(), today());
        return mapper.toCycleResponse(cycle, status, progress);
    }
}
