package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.ModuleRequest;
import com.taskflow.api.dto.request.UpdateModuleRequest;
import com.taskflow.api.dto.response.ModuleResponse;
import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.PlanningMapper;
import com.taskflow.api.repository.ProjectModuleRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.ModuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModuleServiceImpl implements ModuleService {

    private static final String INVALID_DATES = "La fecha objetivo no puede ser anterior a la de inicio";

    private final ProjectModuleRepository moduleRepository;
    private final ActivityService activityService;
    private final PlanningSupport support;
    private final ResourceLookup lookup;
    private final PlanningMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<ModuleResponse> getModules(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        List<ProjectModule> modules = moduleRepository.findAllByProjectIdOrderByNameAsc(project.getId());
        if (modules.isEmpty()) {
            return List.of();
        }
        Map<UUID, ProgressResponse> progress = PlanningSupport.progressById(moduleRepository.findProgress(
                modules.stream().map(ProjectModule::getId).toList(), StateGroup.COMPLETED));
        return modules.stream()
                .map(module -> mapper.toModuleResponse(module,
                        progress.getOrDefault(module.getId(), ProgressResponse.of(0, 0))))
                .toList();
    }

    @Override
    @Transactional
    public ModuleResponse create(String slug, UUID projectId, ModuleRequest request) {
        Project project = lookup.project(slug, projectId);
        PlanningSupport.validateDates(request.startDate(), request.targetDate(), INVALID_DATES);

        ProjectModule module = new ProjectModule();
        module.setProject(project);
        module.setName(request.name().trim());
        module.setDescription(PlanningSupport.blankToNull(request.description()));
        module.setStartDate(request.startDate());
        module.setTargetDate(request.targetDate());
        moduleRepository.save(module);
        return mapper.toModuleResponse(module, ProgressResponse.of(0, 0));
    }

    @Override
    @Transactional(readOnly = true)
    public ModuleResponse get(String slug, UUID projectId, UUID moduleId) {
        return toResponse(findModule(slug, projectId, moduleId));
    }

    @Override
    @Transactional
    public ModuleResponse update(String slug, UUID projectId, UUID moduleId, UpdateModuleRequest request) {
        ProjectModule module = findModule(slug, projectId, moduleId);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            module.setName(request.name().trim());
        }
        if (request.description() != null) {
            module.setDescription(PlanningSupport.blankToNull(request.description()));
        }
        LocalDate startDate = PlanningSupport.editedDate(module.getStartDate(), request.startDate(),
                request.clearDates(), request.clearStartDate());
        LocalDate targetDate = PlanningSupport.editedDate(module.getTargetDate(), request.targetDate(),
                request.clearDates(), request.clearTargetDate());
        PlanningSupport.validateDates(startDate, targetDate, INVALID_DATES);
        module.setStartDate(startDate);
        module.setTargetDate(targetDate);
        return toResponse(moduleRepository.saveAndFlush(module));
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID moduleId) {
        moduleRepository.delete(findModule(slug, projectId, moduleId));
    }

    @Override
    @Transactional
    public ModuleResponse addIssues(String slug, UUID projectId, UUID moduleId, UUID userId, IssueIdsRequest request) {
        ProjectModule module = findModule(slug, projectId, moduleId);
        List<Issue> issues = support.findIssues(module.getProject(), request.issueIds());
        User actor = lookup.user(userId);
        for (Issue issue : issues) {
            if (module.getIssues().add(issue)) {
                activityService.record(issue, actor, ActivityField.MODULE, null, module.getName());
            }
        }
        return toResponse(moduleRepository.saveAndFlush(module));
    }

    @Override
    @Transactional
    public ModuleResponse removeIssue(String slug, UUID projectId, UUID moduleId, UUID issueId, UUID userId) {
        ProjectModule module = findModule(slug, projectId, moduleId);
        Issue issue = module.getIssues().stream()
                .filter(candidate -> candidate.getId().equals(issueId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("La tarea no está en este módulo"));
        module.getIssues().remove(issue);
        activityService.record(issue, lookup.user(userId), ActivityField.MODULE, module.getName(), null);
        return toResponse(moduleRepository.saveAndFlush(module));
    }

    private ProjectModule findModule(String slug, UUID projectId, UUID moduleId) {
        Project project = lookup.project(slug, projectId);
        return moduleRepository.findByIdAndProjectId(moduleId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Módulo no encontrado"));
    }

    private ModuleResponse toResponse(ProjectModule module) {
        return mapper.toModuleResponse(module, PlanningSupport.progressOf(module.getId(),
                ids -> moduleRepository.findProgress(ids, StateGroup.COMPLETED)));
    }
}
