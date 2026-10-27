package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.CycleRequest;
import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.UpdateCycleRequest;
import com.taskflow.api.dto.response.CycleResponse;
import com.taskflow.api.dto.response.CycleStatus;
import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.PlanningMapper;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.CycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CycleServiceImpl implements CycleService {

    private static final String INVALID_DATES = "La fecha de fin no puede ser anterior a la de inicio";

    private final CycleRepository cycleRepository;
    private final ActivityService activityService;
    private final PlanningSupport support;
    private final ResourceLookup lookup;
    private final PlanningMapper mapper;
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
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            cycle.setName(request.name().trim());
        }
        if (request.description() != null) {
            cycle.setDescription(PlanningSupport.blankToNull(request.description()));
        }
        if (Boolean.TRUE.equals(request.clearDates())) {
            cycle.setStartDate(null);
            cycle.setEndDate(null);
        } else {
            if (request.startDate() != null) {
                cycle.setStartDate(request.startDate());
            }
            if (request.endDate() != null) {
                cycle.setEndDate(request.endDate());
            }
        }
        PlanningSupport.validateDates(cycle.getStartDate(), cycle.getEndDate(), INVALID_DATES);
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
        Cycle cycle = findCycle(slug, projectId, cycleId);
        List<Issue> issues = support.findIssues(cycle.getProject(), request.issueIds());
        User actor = lookup.user(userId);

        // Un issue solo puede estar en un ciclo: se saca de cualquier otro antes de agregarlo.
        cycleRepository.removeIssuesFromOtherCycles(cycle.getId(), issues.stream().map(Issue::getId).toList());
        for (Issue issue : issues) {
            if (cycle.getIssues().add(issue)) {
                activityService.record(issue, actor, ActivityField.CYCLE, null, cycle.getName());
            }
        }
        return toResponse(cycleRepository.saveAndFlush(cycle));
    }

    @Override
    @Transactional
    public CycleResponse removeIssue(String slug, UUID projectId, UUID cycleId, UUID issueId, UUID userId) {
        Cycle cycle = findCycle(slug, projectId, cycleId);
        Issue issue = cycle.getIssues().stream()
                .filter(candidate -> candidate.getId().equals(issueId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("La tarea no está en este ciclo"));
        cycle.getIssues().remove(issue);
        activityService.record(issue, lookup.user(userId), ActivityField.CYCLE, cycle.getName(), null);
        return toResponse(cycleRepository.saveAndFlush(cycle));
    }

    private Cycle findCycle(String slug, UUID projectId, UUID cycleId) {
        Project project = lookup.project(slug, projectId);
        return cycleRepository.findByIdAndProjectId(cycleId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ciclo no encontrado"));
    }

    private CycleResponse toResponse(Cycle cycle) {
        return toResponse(cycle, PlanningSupport.progressOf(cycle.getId(),
                ids -> cycleRepository.findProgress(ids, StateGroup.COMPLETED)));
    }

    private CycleResponse toResponse(Cycle cycle, ProgressResponse progress) {
        LocalDate today = LocalDate.now(ZoneId.of(properties.timezone()));
        return mapper.toCycleResponse(cycle, CycleStatus.of(cycle.getStartDate(), cycle.getEndDate(), today), progress);
    }
}
