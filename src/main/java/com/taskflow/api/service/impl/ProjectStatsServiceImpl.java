package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.response.ProjectStatsResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.repository.IssueAssigneeRow;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueStatsRow;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.service.ProjectStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lee los datos del proyecto con pocas consultas fijas (tareas, asignados, estados y ciclo en curso) y
 * delega el cálculo en {@link ProjectStatsCalculator}.
 */
@Service
@RequiredArgsConstructor
public class ProjectStatsServiceImpl implements ProjectStatsService {

    static final Set<Integer> ALLOWED_WEEKS = Set.of(4, 8, 12);

    private final IssueRepository issueRepository;
    private final StateRepository stateRepository;
    private final CycleRepository cycleRepository;
    private final ResourceLookup lookup;
    private final UserMapper userMapper;
    private final AppProperties properties;

    @Override
    @Transactional(readOnly = true)
    public ProjectStatsResponse getStats(String slug, UUID projectId, int weeks, Collection<UUID> assignees) {
        if (!ALLOWED_WEEKS.contains(weeks)) {
            throw new BadRequestException("El parámetro weeks debe ser 4, 8 o 12");
        }
        Project project = lookup.project(slug, projectId);
        ZoneId zone = ZoneId.of(properties.timezone());
        Instant now = Instant.now();
        Set<UUID> people = assignees == null ? Set.of() : new HashSet<>(assignees);

        List<IssueStatsRow> issues = issueRepository.findStatsRows(project.getId());
        List<IssueAssigneeRow> assigneeRows = issueRepository.findAssignees(project.getId(), StateGroup.CLOSED);
        if (!people.isEmpty()) {
            // Filtro por persona: solo sus tareas, y en la carga solo esas personas (no sus compañeros de tarea).
            Set<UUID> theirIssues = new HashSet<>(issueRepository.findIdsAssignedToAny(project.getId(), people));
            issues = issues.stream().filter(issue -> theirIssues.contains(issue.id())).toList();
            assigneeRows = assigneeRows.stream().filter(row -> people.contains(row.user().getId())).toList();
        }
        Map<UUID, List<UserSummaryResponse>> assigneesByIssue = ProjectStatsCalculator.groupBy(
                assigneeRows, IssueAssigneeRow::issueId, row -> userMapper.toSummary(row.user()));
        Cycle currentCycle = PlanningSupport.currentCycle(cycleRepository.findAllByProjectId(project.getId()),
                LocalDate.ofInstant(now, zone));
        Set<UUID> cycleIssueIds = currentCycle == null
                ? Set.of()
                : new HashSet<>(cycleRepository.findIssueIds(currentCycle.getId()));

        // Con el filtro, cada tarea tiene a alguna de esas personas: la carga no tiene fila «sin asignar».
        return new ProjectStatsCalculator(now, zone).calculate(
                project.getIdentifier(),
                weeks,
                issues,
                stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId()),
                assigneesByIssue,
                currentCycle,
                cycleIssueIds);
    }
}
