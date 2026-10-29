package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.MyWorkView;
import com.taskflow.api.dto.response.MyWorkResponse;
import com.taskflow.api.dto.response.MyWorkResponse.StateCount;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.MyWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MyWorkServiceImpl implements MyWorkService {

    /** Fecha límite más próxima primero (sin fecha al final), luego prioridad (URGENT primero) y número. */
    static final Comparator<Issue> MY_WORK_ORDER = Comparator
            .comparing(Issue::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Issue::getPriority)
            .thenComparingInt(Issue::getSequenceId);

    private static final Set<StateGroup> CLOSED = StateGroup.CLOSED;

    private final IssueRepository issueRepository;
    private final ProjectAccess projectAccess;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;
    private final AppProperties properties;

    @Override
    @Transactional(readOnly = true)
    public MyWorkResponse getMyWork(String slug, UUID userId, MyWorkView view) {
        Workspace workspace = lookup.workspace(slug);
        List<UUID> projectIds = projectAccess.projectIds(workspace, userId);
        if (projectIds.isEmpty()) {
            return new MyWorkResponse(List.of(), 0, 0, 0, 0, 0, 0, 0, List.of(), null);
        }

        // Las asignadas se leen siempre: de ellas salen los contadores, sea cual sea la vista.
        List<Issue> assigned = issueRepository.findAssignedTo(projectIds, userId, CLOSED);
        List<Issue> issues = switch (view) {
            case ASSIGNED -> assigned;
            case CREATED -> issueRepository.findCreatedBy(projectIds, userId, CLOSED);
            case COMMENTED -> issueRepository.findCommentedBy(projectIds, userId, CLOSED);
        };
        ZoneId zone = ZoneId.of(properties.timezone());
        LocalDate today = LocalDate.now(zone);
        Instant weekStart = ProjectStatsCalculator.startOfWeek(today).atStartOfDay(zone).toInstant();
        Issue nextDue = nextDue(assigned, today);

        return new MyWorkResponse(
                issues.stream().sorted(MY_WORK_ORDER).map(issueMapper::toResponse).toList(),
                assigned.size(),
                issueRepository.countCreatedBy(projectIds, userId, CLOSED),
                issueRepository.countCommentedBy(projectIds, userId, CLOSED),
                issueRepository.countAssignedCompletedSince(projectIds, userId, weekStart),
                assigned.stream().filter(issue -> issue.getState().getStateGroup() == StateGroup.STARTED).count(),
                assigned.stream().filter(issue -> issue.getDueDate() != null && issue.getDueDate().isBefore(today))
                        .count(),
                assigned.stream().map(issue -> issue.getProject().getId()).distinct().count(),
                byState(assigned),
                nextDue == null ? null : issueMapper.toRelated(nextDue));
    }

    /** La de fecha límite más próxima después de hoy (empate: prioridad y número); {@code null} si no hay. */
    static Issue nextDue(List<Issue> issues, LocalDate today) {
        return issues.stream()
                .filter(issue -> issue.getDueDate() != null && issue.getDueDate().isAfter(today))
                .min(MY_WORK_ORDER)
                .orElse(null);
    }

    /**
     * Tareas por estado. Los estados de distintos proyectos con el mismo grupo y el mismo nombre (sin importar
     * mayúsculas) se suman; nombre y color, los del estado que va primero en su tablero. Por grupo y posición.
     */
    static List<StateCount> byState(List<Issue> issues) {
        Map<String, List<State>> statesByKey = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Issue issue : issues) {
            State state = issue.getState();
            String key = state.getStateGroup() + "|" + state.getName().strip().toLowerCase(Locale.ROOT);
            statesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(state);
            counts.merge(key, 1L, Long::sum);
        }
        Comparator<State> boardOrder = Comparator.comparing(State::getStateGroup)
                .thenComparingInt(State::getPosition)
                .thenComparing(State::getName, String.CASE_INSENSITIVE_ORDER);
        return statesByKey.entrySet().stream()
                .map(entry -> Map.entry(entry.getValue().stream().min(boardOrder).orElseThrow(),
                        counts.get(entry.getKey())))
                .sorted(Map.Entry.comparingByKey(boardOrder))
                .map(entry -> new StateCount(entry.getKey().getName(), entry.getKey().getColor(),
                        entry.getKey().getStateGroup(), entry.getValue()))
                .toList();
    }
}
