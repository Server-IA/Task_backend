package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.RetroNoteRequest;
import com.taskflow.api.dto.request.UpdateRetroNoteRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.RetroNoteResponse;
import com.taskflow.api.dto.response.RetroResponse;
import com.taskflow.api.dto.response.RetroResponse.RetroSummary;
import com.taskflow.api.dto.response.RetroVoteResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.RetroNote;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.RetroColumn;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.PlanningMapper;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.RetroNoteRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.service.RetroService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetroServiceImpl implements RetroService {

    /** Votos de cada usuario por retrospectiva. */
    static final int MAX_VOTES = 5;
    /** Largo máximo del título de una tarea. */
    static final int MAX_TITLE_LENGTH = 255;

    static final String NOT_STARTED_MESSAGE = "La retrospectiva todavía no ha empezado";
    static final String FINISHED_MESSAGE = "La retrospectiva ya terminó: sus notas y votos son de solo lectura";
    static final String ALREADY_VOTED_MESSAGE = "Ya votaste por esta nota";
    static final String NO_VOTES_LEFT_MESSAGE = "Ya usaste tus " + MAX_VOTES + " votos en esta retrospectiva";
    static final String ONLY_ACTIONS_MESSAGE = "Solo las acciones se pueden convertir en tarea";
    static final String ONLY_ACTIONS_OWNER_MESSAGE = "Solo las acciones tienen responsable";
    static final String OWNER_NOT_MEMBER_MESSAGE = "El responsable debe ser miembro del proyecto";

    private final CycleRepository cycleRepository;
    private final RetroNoteRepository noteRepository;
    private final StateRepository stateRepository;
    private final IssueRepository issueRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueService issueService;
    private final NotificationService notificationService;
    private final ProjectAccess projectAccess;
    private final ResourceLookup lookup;
    private final PlanningMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public RetroResponse getRetro(String slug, UUID projectId, UUID cycleId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        return toResponse(project, findCycle(project, cycleId), userId);
    }

    @Override
    @Transactional
    public RetroResponse start(String slug, UUID projectId, UUID cycleId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        Cycle cycle = findCycle(project, cycleId);
        if (cycle.getRetroStartedAt() == null) {
            cycle.setRetroStartedAt(Instant.now());
            cycleRepository.saveAndFlush(cycle);
            notificationService.notifyRetroStarted(cycle, lookup.user(userId));
        }
        return toResponse(project, cycle, userId);
    }

    @Override
    @Transactional
    public RetroNoteResponse createNote(String slug, UUID projectId, UUID cycleId, UUID userId,
                                        RetroNoteRequest request) {
        Project project = lookup.project(slug, projectId);
        Cycle cycle = findCycle(project, cycleId);
        ensureOpen(cycle);
        RetroNote note = new RetroNote(cycle, request.column(), request.text().strip(), lookup.user(userId));
        if (request.ownerId() != null) {
            note.setOwner(findOwner(project, note, request.ownerId()));
        }
        return toNoteResponse(noteRepository.save(note), userId);
    }

    @Override
    @Transactional
    public RetroNoteResponse updateNote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId,
                                        UpdateRetroNoteRequest request) {
        Project project = lookup.project(slug, projectId);
        Cycle cycle = findCycle(project, cycleId);
        ensureOpen(cycle);
        RetroNote note = findNote(cycle, noteId);
        ensureAuthorOrManager(project, note, userId, "editarla");
        if (request.text() != null) {
            if (request.text().isBlank()) {
                throw new BadRequestException("El texto no puede estar vacío");
            }
            note.setText(request.text().strip());
        }
        if (Boolean.TRUE.equals(request.clearOwner())) {
            note.setOwner(null);
        } else if (request.ownerId() != null) {
            note.setOwner(findOwner(project, note, request.ownerId()));
        }
        // El flush ejecuta @PreUpdate para que la respuesta lleve el updatedAt nuevo.
        return toNoteResponse(noteRepository.saveAndFlush(note), userId);
    }

    @Override
    @Transactional
    public void deleteNote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        Cycle cycle = findCycle(project, cycleId);
        ensureOpen(cycle);
        RetroNote note = findNote(cycle, noteId);
        ensureAuthorOrManager(project, note, userId, "eliminarla");
        noteRepository.delete(note);
    }

    @Override
    @Transactional
    public RetroVoteResponse vote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        // Con la fila del ciclo bloqueada, los votos se cuentan de a uno y nadie supera el máximo.
        Cycle cycle = cycleRepository.findForUpdate(cycleId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ciclo no encontrado"));
        ensureOpen(cycle);
        RetroNote note = findNote(cycle, noteId);
        if (note.isVotedBy(userId)) {
            throw new ConflictException(ALREADY_VOTED_MESSAGE);
        }
        long used = noteRepository.countVotesByUser(cycle.getId(), userId);
        if (used >= MAX_VOTES) {
            throw new ConflictException(NO_VOTES_LEFT_MESSAGE);
        }
        note.getVoters().add(lookup.user(userId));
        noteRepository.saveAndFlush(note);
        return new RetroVoteResponse(toNoteResponse(note, userId), votesLeft(used + 1));
    }

    @Override
    @Transactional
    public RetroVoteResponse unvote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId) {
        Cycle cycle = findCycle(lookup.project(slug, projectId), cycleId);
        ensureOpen(cycle);
        RetroNote note = findNote(cycle, noteId);
        long used = noteRepository.countVotesByUser(cycle.getId(), userId);
        if (note.getVoters().removeIf(voter -> voter.getId().equals(userId))) {
            used--;
            noteRepository.saveAndFlush(note);
        }
        return new RetroVoteResponse(toNoteResponse(note, userId), votesLeft(used));
    }

    @Override
    @Transactional
    public IssueResponse convert(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        // Bloquea el ciclo para que dos clics seguidos no creen dos tareas.
        Cycle cycle = cycleRepository.findForUpdate(cycleId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ciclo no encontrado"));
        RetroNote note = findNote(cycle, noteId);
        if (note.getColumn() != RetroColumn.ACTION) {
            throw new BadRequestException(ONLY_ACTIONS_MESSAGE);
        }
        if (note.getIssue() != null) {
            throw new ConflictException("Esta acción ya se convirtió en la tarea " + note.getIssue().getCode());
        }
        // Sin estados «sin empezar», la tarea va al estado por defecto (el primero del tablero).
        UUID stateId = stateRepository
                .findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(), StateGroup.UNSTARTED)
                .map(State::getId)
                .orElse(null);
        String title = issueTitle(note.getText());
        // Se asigna al responsable si sigue siendo miembro del proyecto (si no, queda sin asignar).
        User owner = note.getOwner();
        List<UUID> assigneeIds = owner != null
                && projectMemberRepository.existsByProjectIdAndUserId(project.getId(), owner.getId())
                ? List.of(owner.getId())
                : null;
        IssueResponse issue = issueService.create(slug, projectId, userId, new CreateIssueRequest(
                title, issueDescription(cycle, note.getText(), title), stateId,
                null, null, null, null, assigneeIds, null, null, null));
        note.setIssue(issueRepository.getReferenceById(issue.id()));
        noteRepository.save(note);
        return issue;
    }

    @Override
    @Transactional
    public RetroResponse finish(String slug, UUID projectId, UUID cycleId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        Cycle cycle = findCycle(project, cycleId);
        if (cycle.getRetroStartedAt() == null) {
            throw new ConflictException(NOT_STARTED_MESSAGE);
        }
        if (cycle.getRetroFinishedAt() == null) {
            cycle.setRetroFinishedAt(Instant.now());
            cycleRepository.saveAndFlush(cycle);
        }
        return toResponse(project, cycle, userId);
    }

    /** Título de la tarea: el texto de la acción en una línea, cortado con «…» si no cabe. */
    static String issueTitle(String text) {
        String title = text.strip().replaceAll("\\s+", " ");
        return title.length() <= MAX_TITLE_LENGTH
                ? title
                : title.substring(0, MAX_TITLE_LENGTH - 1).stripTrailing() + "…";
    }

    /** Descripción (markdown) que apunta a la retrospectiva; si el título no es el texto completo, lo incluye. */
    static String issueDescription(Cycle cycle, String text, String title) {
        String description = "Acción acordada en la retrospectiva del ciclo «" + cycle.getName() + "».";
        return title.equals(text.strip())
                ? description
                : description + "\n\n> " + text.strip().replace("\n", "\n> ");
    }

    /** El último ciclo cerrado antes que este (o el último cerrado, si este sigue abierto); {@code null} si no hay. */
    static Cycle previousClosedCycle(Cycle cycle, Collection<Cycle> cycles) {
        return cycles.stream()
                .filter(other -> other.isClosed() && !other.getId().equals(cycle.getId()))
                .filter(other -> !cycle.isClosed() || other.getClosedAt().isBefore(cycle.getClosedAt()))
                .max(Comparator.comparing(Cycle::getClosedAt))
                .orElse(null);
    }

    private RetroResponse toResponse(Project project, Cycle cycle, UUID userId) {
        boolean started = cycle.getRetroStartedAt() != null;
        List<RetroNote> notes = started ? noteRepository.findAllByCycleId(cycle.getId()) : List.of();
        long used = notes.stream().filter(note -> note.isVotedBy(userId)).count();
        return new RetroResponse(
                started,
                cycle.getRetroFinishedAt(),
                summary(cycle),
                notes.stream().map(note -> toNoteResponse(note, userId)).toList(),
                votesLeft(used),
                previousActions(project, cycle, userId));
    }

    /** Las cuentas de lo que se movió vienen del cierre; las tareas que siguen en el ciclo se cuentan ahora. */
    private static RetroSummary summary(Cycle cycle) {
        Collection<Issue> issues = cycle.getIssues();
        int completed = (int) issues.stream().filter(PlanningSupport::isCompleted).count();
        return new RetroSummary(
                completed,
                issues.size() + cycle.getCarriedToCycle() + cycle.getCarriedToBacklog(),
                cycle.getCarriedToCycle(),
                cycle.getCarriedToBacklog(),
                PlanningSupport.cycleTimeDays(issues));
    }

    private List<RetroNoteResponse> previousActions(Project project, Cycle cycle, UUID userId) {
        Cycle previous = previousClosedCycle(cycle, cycleRepository.findAllByProjectId(project.getId()));
        if (previous == null) {
            return List.of();
        }
        return noteRepository.findAllByCycleId(previous.getId()).stream()
                .filter(note -> note.getColumn() == RetroColumn.ACTION)
                .map(note -> toNoteResponse(note, userId))
                .toList();
    }

    /** Notas y votos solo mientras la retrospectiva está abierta. */
    private static void ensureOpen(Cycle cycle) {
        if (cycle.getRetroStartedAt() == null) {
            throw new ConflictException(NOT_STARTED_MESSAGE);
        }
        if (cycle.getRetroFinishedAt() != null) {
            throw new ConflictException(FINISHED_MESSAGE);
        }
    }

    /** Responsable de una acción: solo las acciones lo tienen y debe ser miembro del proyecto (si no, 400). */
    private User findOwner(Project project, RetroNote note, UUID ownerId) {
        if (note.getColumn() != RetroColumn.ACTION) {
            throw new BadRequestException(ONLY_ACTIONS_OWNER_MESSAGE);
        }
        if (!projectMemberRepository.existsByProjectIdAndUserId(project.getId(), ownerId)) {
            throw new BadRequestException(OWNER_NOT_MEMBER_MESSAGE);
        }
        return lookup.user(ownerId);
    }

    private void ensureAuthorOrManager(Project project, RetroNote note, UUID userId, String action) {
        if (!note.getAuthor().getId().equals(userId) && !projectAccess.isManager(project.getWorkspace(), userId)) {
            throw new ForbiddenException("Solo el autor de la nota o el project manager pueden " + action);
        }
    }

    private static int votesLeft(long used) {
        return (int) Math.max(0, MAX_VOTES - used);
    }

    private RetroNoteResponse toNoteResponse(RetroNote note, UUID userId) {
        return mapper.toRetroNoteResponse(note, note.getVoters().size(), note.isVotedBy(userId));
    }

    private RetroNote findNote(Cycle cycle, UUID noteId) {
        return noteRepository.findByIdAndCycleId(noteId, cycle.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Nota no encontrada"));
    }

    private Cycle findCycle(Project project, UUID cycleId) {
        return cycleRepository.findByIdAndProjectId(cycleId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ciclo no encontrado"));
    }
}
