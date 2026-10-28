package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.IssueTemplateRequest;
import com.taskflow.api.dto.request.UpdateIssueTemplateRequest;
import com.taskflow.api.dto.response.IssueTemplateResponse;
import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.TemplateChecklistItem;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.service.IssueTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IssueTemplateServiceImpl implements IssueTemplateService {

    private final IssueTemplateRepository templateRepository;
    private final LabelRepository labelRepository;
    private final ResourceLookup lookup;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<IssueTemplateResponse> getTemplates(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        return ordered(project).stream().map(projectMapper::toTemplateResponse).toList();
    }

    @Override
    @Transactional
    public IssueTemplateResponse create(String slug, UUID projectId, IssueTemplateRequest request) {
        Project project = lookup.project(slug, projectId);
        String name = request.name().trim();
        ensureNameAvailable(project, name);

        List<IssueTemplate> existing = ordered(project);
        IssueTemplate template = new IssueTemplate();
        template.setProject(project);
        template.setName(name);
        template.setTitlePrefix(blankToNull(request.titlePrefix(), false));
        template.setDescription(blankToNull(request.description(), true));
        template.setPriority(priorityOrNull(request.priority()));
        template.getLabels().addAll(resolveLabels(project, request.labelIds()));
        template.getChecklist().addAll(toItems(request.checklist()));
        template.setPosition(existing.isEmpty() ? 0 : existing.getLast().getPosition() + 1);
        return projectMapper.toTemplateResponse(templateRepository.save(template));
    }

    @Override
    @Transactional
    public IssueTemplateResponse update(String slug, UUID projectId, UUID templateId,
                                        UpdateIssueTemplateRequest request) {
        Project project = lookup.project(slug, projectId);
        IssueTemplate template = findTemplate(project, templateId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            if (!name.equalsIgnoreCase(template.getName())) {
                ensureNameAvailable(project, name);
            }
            template.setName(name);
        }
        if (request.titlePrefix() != null) {
            template.setTitlePrefix(blankToNull(request.titlePrefix(), false));
        }
        if (request.description() != null) {
            template.setDescription(blankToNull(request.description(), true));
        }
        if (request.priority() != null) {
            template.setPriority(priorityOrNull(request.priority()));
        }
        if (request.labelIds() != null) {
            Set<Label> labels = resolveLabels(project, request.labelIds());
            template.getLabels().clear();
            template.getLabels().addAll(labels);
        }
        if (request.checklist() != null) {
            template.getChecklist().clear();
            template.getChecklist().addAll(toItems(request.checklist()));
        }
        if (request.position() != null) {
            List<IssueTemplate> ordered = new ArrayList<>(ordered(project));
            ordered.removeIf(other -> other.getId().equals(template.getId()));
            ordered.add(Math.min(request.position(), ordered.size()), template);
            renumber(ordered);
        }
        // El flush ejecuta @PreUpdate y valida la unicidad del nombre antes de responder.
        return projectMapper.toTemplateResponse(templateRepository.saveAndFlush(template));
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID templateId) {
        Project project = lookup.project(slug, projectId);
        IssueTemplate template = findTemplate(project, templateId);
        templateRepository.delete(template);
        renumber(ordered(project).stream().filter(other -> !other.getId().equals(template.getId())).toList());
    }

    private List<IssueTemplate> ordered(Project project) {
        return templateRepository.findAllByProjectIdOrderByPositionAscCreatedAtAsc(project.getId());
    }

    private IssueTemplate findTemplate(Project project, UUID templateId) {
        return templateRepository.findByIdAndProjectId(templateId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Plantilla no encontrada"));
    }

    private void ensureNameAvailable(Project project, String name) {
        if (templateRepository.existsByProjectIdAndNameIgnoreCase(project.getId(), name)) {
            throw new ConflictException("Ya existe una plantilla llamada '" + name + "'");
        }
    }

    /** Etiquetas del proyecto ordenadas por nombre (el mismo orden con el que se leen después). */
    private Set<Label> resolveLabels(Project project, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<UUID> distinct = new HashSet<>(ids);
        List<Label> labels = labelRepository.findAllByProjectIdAndIdIn(project.getId(), distinct);
        if (labels.size() != distinct.size()) {
            throw new BadRequestException("Las etiquetas deben pertenecer al proyecto");
        }
        Set<Label> sorted = new LinkedHashSet<>();
        labels.stream().sorted(Comparator.comparing(Label::getName)).forEach(sorted::add);
        return sorted;
    }

    private static List<TemplateChecklistItem> toItems(List<ChecklistItemRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream()
                .map(request -> new TemplateChecklistItem(request.kind(), request.text().trim()))
                .toList();
    }

    /** {@code NONE} equivale a que la plantilla no fije prioridad. */
    private static IssuePriority priorityOrNull(IssuePriority priority) {
        return priority == IssuePriority.NONE ? null : priority;
    }

    private static void renumber(List<IssueTemplate> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
    }

    /**
     * Vacío = {@code null}. El prefijo se guarda tal cual (su espacio final importa, «[Bug] »); la descripción
     * sin espacios sobrantes al principio y al final.
     */
    private static String blankToNull(String value, boolean trim) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return trim ? value.trim() : value;
    }
}
