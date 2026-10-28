package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.DailyNoteRequest;
import com.taskflow.api.dto.response.DailyNoteResponse;
import com.taskflow.api.dto.response.DailyResponse;
import com.taskflow.api.dto.response.DailyResponse.DailyBlocker;
import com.taskflow.api.dto.response.DailyResponse.DailyChange;
import com.taskflow.api.dto.response.DailyResponse.DailyPerson;
import com.taskflow.api.entity.DailyNote;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueActivity;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.DailyNoteRepository;
import com.taskflow.api.repository.IssueActivityRepository;
import com.taskflow.api.repository.IssueRelationRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.service.DailyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Arma la daily con pocas consultas fijas (tareas abiertas asignadas, sus bloqueos, la actividad del periodo,
 * los miembros, los estados y la nota) y agrupa en memoria por persona.
 */
@Service
@RequiredArgsConstructor
public class DailyServiceImpl implements DailyService {

    /** Máximo de entradas en «ayer» y en «hoy» por persona. */
    static final int MAX_ITEMS = 8;

    static final String COMPLETED = "completó";
    static final String MOVED_TO = "movió a ";
    static final String CREATED = "creó";
    static final String COMMENTED = "comentó";
    static final String UPDATED = "actualizó";

    /** Por nombre (sin importar mayúsculas) y, si coinciden, por correo. */
    private static final Comparator<User> BY_NAME = Comparator
            .comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(User::getEmail);

    private final IssueRepository issueRepository;
    private final IssueActivityRepository activityRepository;
    private final IssueRelationRepository relationRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final StateRepository stateRepository;
    private final DailyNoteRepository noteRepository;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;
    private final UserMapper userMapper;
    private final CollaborationMapper collaborationMapper;
    private final AppProperties properties;

    @Override
    @Transactional(readOnly = true)
    public DailyResponse getDaily(String slug, UUID projectId, LocalDate date) {
        Project project = lookup.project(slug, projectId);
        ZoneId zone = ZoneId.of(properties.timezone());
        LocalDate day = date != null ? date : LocalDate.now(zone);
        LocalDate previous = previousWorkingDay(day);

        // Tareas abiertas con asignados, agrupadas por persona (una con dos asignados cuenta para ambos).
        List<Issue> openIssues = issueRepository.findAssignedInProject(project.getId(), StateGroup.CLOSED);
        Map<UUID, User> people = new HashMap<>();
        Map<UUID, List<Issue>> issuesByPerson = new HashMap<>();
        for (ProjectMember member : projectMemberRepository.findAllByProjectIdWithUser(project.getId())) {
            people.putIfAbsent(member.getUser().getId(), member.getUser());
        }
        for (Issue issue : openIssues) {
            for (User assignee : issue.getAssignees()) {
                people.putIfAbsent(assignee.getId(), assignee);
                issuesByPerson.computeIfAbsent(assignee.getId(), id -> new ArrayList<>()).add(issue);
            }
        }
        Map<UUID, List<Issue>> blockersByIssue = openBlockers(openIssues);

        // Lo hecho desde el día hábil anterior hasta antes del día de la reunión (el lunes incluye el fin de semana).
        Instant from = previous.atStartOfDay(zone).toInstant();
        Instant to = day.atStartOfDay(zone).toInstant();
        Map<UUID, List<IssueActivity>> activityByPerson = activityRepository
                .findAllInProjectBetween(project.getId(), from, to).stream()
                .collect(Collectors.groupingBy(activity -> activity.getActor().getId()));
        Map<String, StateGroup> groupByStateName = stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId())
                .stream()
                .collect(Collectors.toMap(state -> key(state.getName()), State::getStateGroup, (a, b) -> a));

        List<DailyPerson> result = people.values().stream()
                .sorted(BY_NAME)
                .map(user -> {
                    List<Issue> assigned = issuesByPerson.getOrDefault(user.getId(), List.of());
                    return new DailyPerson(
                            userMapper.toSummary(user),
                            yesterday(activityByPerson.getOrDefault(user.getId(), List.of()), groupByStateName),
                            todayIssues(assigned, day).stream().map(issueMapper::toRelated).toList(),
                            blockers(assigned, blockersByIssue));
                })
                .toList();
        DailyNoteResponse note = noteRepository.findByProjectIdAndDate(project.getId(), day)
                .map(collaborationMapper::toDailyNoteResponse)
                .orElse(null);
        return new DailyResponse(day, previous, result, note);
    }

    @Override
    @Transactional
    public Optional<DailyNoteResponse> saveNote(String slug, UUID projectId, UUID userId, LocalDate date,
                                                DailyNoteRequest request) {
        Project project = lookup.project(slug, projectId);
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of(properties.timezone()));
        String text = request.text() == null ? "" : request.text().strip();
        Optional<DailyNote> existing = noteRepository.findByProjectIdAndDate(project.getId(), day);
        if (text.isEmpty()) {
            existing.ifPresent(noteRepository::delete);
            return Optional.empty();
        }
        DailyNote note = existing.orElseGet(() -> new DailyNote(project, day));
        note.setText(text);
        note.setUpdatedBy(lookup.user(userId));
        // El flush ejecuta @PreUpdate para que la respuesta lleve el updatedAt nuevo.
        return Optional.of(collaborationMapper.toDailyNoteResponse(noteRepository.saveAndFlush(note)));
    }

    /** Día hábil anterior: el día antes o, si es lunes, sábado o domingo, el viernes anterior. */
    static LocalDate previousWorkingDay(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> date.minusDays(3);
            case SUNDAY -> date.minusDays(2);
            default -> date.minusDays(1);
        };
    }

    /**
     * Una entrada por tarea con el cambio más importante de la persona (completar, mover de estado, crear, comentar
     * y, al final, cualquier otro; entre dos iguales, el más reciente). Las más importantes primero, máximo 8.
     */
    List<DailyChange> yesterday(List<IssueActivity> activities, Map<String, StateGroup> groupByStateName) {
        Map<UUID, Change> best = new LinkedHashMap<>();
        for (IssueActivity activity : activities) {
            best.merge(activity.getIssue().getId(), Change.of(activity, groupByStateName),
                    (current, candidate) -> candidate.rank() <= current.rank() ? candidate : current);
        }
        return best.values().stream()
                .sorted(Comparator.comparingInt(Change::rank).thenComparing(Change::at, Comparator.reverseOrder()))
                .limit(MAX_ITEMS)
                .map(change -> new DailyChange(issueMapper.toRelated(change.issue()), change.label()))
                .toList();
    }

    /** Abiertas en curso (grupo STARTED) o con fecha límite hasta el día de la reunión, como en «Mi trabajo». */
    static List<Issue> todayIssues(List<Issue> assigned, LocalDate day) {
        return assigned.stream()
                .filter(issue -> issue.getState().getStateGroup() == StateGroup.STARTED
                        || issue.getDueDate() != null && !issue.getDueDate().isAfter(day))
                .sorted(MyWorkServiceImpl.MY_WORK_ORDER)
                .limit(MAX_ITEMS)
                .toList();
    }

    private List<DailyBlocker> blockers(List<Issue> assigned, Map<UUID, List<Issue>> blockersByIssue) {
        return assigned.stream()
                .filter(issue -> blockersByIssue.containsKey(issue.getId()))
                .sorted(MyWorkServiceImpl.MY_WORK_ORDER)
                .map(issue -> new DailyBlocker(issueMapper.toRelated(issue),
                        blockersByIssue.get(issue.getId()).stream()
                                .sorted(Comparator.comparingInt(Issue::getSequenceId))
                                .map(issueMapper::toSummary)
                                .toList()))
                .toList();
    }

    /** Bloqueos abiertos de las tareas que los tienen ({@code blockedByCount > 0}), por tarea bloqueada. */
    private Map<UUID, List<Issue>> openBlockers(List<Issue> issues) {
        List<UUID> blocked = issues.stream()
                .filter(issue -> issue.getBlockedByCount() > 0)
                .map(Issue::getId)
                .toList();
        if (blocked.isEmpty()) {
            return Map.of();
        }
        return relationRepository.findOpenBlockersOf(blocked, StateGroup.CLOSED).stream()
                .collect(Collectors.groupingBy(relation -> relation.getTarget().getId(),
                        Collectors.mapping(IssueRelation::getSource, Collectors.toList())));
    }

    /** El historial guarda el nombre del estado: se compara sin importar mayúsculas. */
    private static String key(String stateName) {
        return stateName.toLowerCase(Locale.ROOT);
    }

    /**
     * Un cambio de la persona en una tarea. {@code rank}: 0 completó, 1 movió de estado, 2 creó, 3 comentó,
     * 4 cualquier otro.
     */
    record Change(Issue issue, String label, int rank, Instant at) {

        static Change of(IssueActivity activity, Map<String, StateGroup> groupByStateName) {
            Issue issue = activity.getIssue();
            Instant at = activity.getCreatedAt();
            return switch (activity.getField()) {
                case STATE -> {
                    String state = activity.getNewValue();
                    if (state == null) {
                        yield new Change(issue, UPDATED, 4, at);
                    }
                    yield groupByStateName.get(key(state)) == StateGroup.COMPLETED
                            ? new Change(issue, COMPLETED, 0, at)
                            : new Change(issue, MOVED_TO + state, 1, at);
                }
                case CREATED -> new Change(issue, CREATED, 2, at);
                case COMMENT -> new Change(issue, COMMENTED, 3, at);
                default -> new Change(issue, UPDATED, 4, at);
            };
        }
    }
}
