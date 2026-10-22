package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.LabelRequest;
import com.taskflow.api.dto.response.LabelResponse;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.service.LabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LabelServiceImpl implements LabelService {

    private final LabelRepository labelRepository;
    private final ResourceLookup lookup;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<LabelResponse> getLabels(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        return labelRepository.findAllByProjectIdOrderByNameAsc(project.getId()).stream()
                .map(projectMapper::toLabelResponse)
                .toList();
    }

    @Override
    @Transactional
    public LabelResponse create(String slug, UUID projectId, LabelRequest request) {
        Project project = lookup.project(slug, projectId);
        String name = request.name().trim();
        ensureNameAvailable(project, name);

        Label label = new Label();
        label.setProject(project);
        label.setName(name);
        label.setColor(request.color().toUpperCase(Locale.ROOT));
        return projectMapper.toLabelResponse(labelRepository.save(label));
    }

    @Override
    @Transactional
    public LabelResponse update(String slug, UUID projectId, UUID labelId, LabelRequest request) {
        Project project = lookup.project(slug, projectId);
        Label label = labelRepository.findByIdAndProjectId(labelId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Etiqueta no encontrada"));
        String name = request.name().trim();
        if (!name.equalsIgnoreCase(label.getName())) {
            ensureNameAvailable(project, name);
        }
        label.setName(name);
        label.setColor(request.color().toUpperCase(Locale.ROOT));
        return projectMapper.toLabelResponse(label);
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID labelId) {
        Project project = lookup.project(slug, projectId);
        Label label = labelRepository.findByIdAndProjectId(labelId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Etiqueta no encontrada"));
        labelRepository.delete(label);
    }

    private void ensureNameAvailable(Project project, String name) {
        if (labelRepository.existsByProjectIdAndNameIgnoreCase(project.getId(), name)) {
            throw new ConflictException("Ya existe una etiqueta llamada '" + name + "'");
        }
    }
}
