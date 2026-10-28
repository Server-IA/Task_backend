package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.response.CycleStatus;
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
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.service.ProjectStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    public ProjectStatsResponse getStats(String slug, UUID projectId, int weeks) {
        if (!ALLOWED_WEEKS.contains(weeks)) {
            throw new BadRequestException("El parámetro weeks debe ser 4, 8 o 12");
        }
        Project project = lookup.project(slug, projectId);
        ZoneId zone = ZoneId.of(properties.timezone());
        Instant now = Instant.now();

        List<IssueAssigneeRow> assigneeRows = issueRepository.findAssignees(project.getId(), StateGroup.CLOSED);
        Map<UUID, List<UserSummaryResponse>> assigneesByIssue = ProjectStatsCalculator.groupBy(
                assigneeRows, IssueAssigneeRow::issueId, row -> userMapper.toSummary(row.user()));
        Cycle currentCycle = findCurrentCycle(project, LocalDate.ofInstant(now, zone));
        Set<UUID> cycleIssueIds = currentCycle == null
                ? Set.of()
                : new HashSet<>(cycleRepository.findIssueIds(currentCycle.getId()));

        return new ProjectStatsCalculator(now, zone).calculate(
                project.getIdentifier(),
                weeks,
                issueRepository.findStatsRows(project.getId()),
                stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId()),
                assigneesByIssue,
                currentCycle,
                cycleIssueIds);
    }

    /** Ciclo en curso (hoy entre sus fechas y sin cerrar). Si se solapan varios, el que empezó más tarde. */
    private Cycle findCurrentCycle(Project project, LocalDate today) {
        return cycleRepository.findAllByProjectId(project.getId()).stream()
                .filter(cycle -> CycleStatus.of(cycle.getStartDate(), cycle.getEndDate(), cycle.isClosed(), today)
                        == CycleStatus.CURRENT)
                .findFirst()
                .orElse(null);
    }
}
