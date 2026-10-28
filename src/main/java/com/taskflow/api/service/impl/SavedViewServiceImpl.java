package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateSavedViewRequest;
import com.taskflow.api.dto.request.UpdateSavedViewRequest;
import com.taskflow.api.dto.response.SavedViewResponse;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.SavedView;
import com.taskflow.api.entity.SavedViewFilters;
import com.taskflow.api.entity.User;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.SavedViewRepository;
import com.taskflow.api.service.SavedViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SavedViewServiceImpl implements SavedViewService {

    private final SavedViewRepository viewRepository;
    private final ProjectAccess projectAccess;
    private final ResourceLookup lookup;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<SavedViewResponse> getViews(String slug, UUID projectId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        return viewRepository.findVisible(project.getId(), userId).stream()
                .map(view -> toResponse(view, userId))
                .toList();
    }

    @Override
    @Transactional
    public SavedViewResponse create(String slug, UUID projectId, UUID userId, CreateSavedViewRequest request) {
        Project project = lookup.project(slug, projectId);
        boolean shared = Boolean.TRUE.equals(request.shared());
        if (shared && !projectAccess.isManager(project.getWorkspace(), userId)) {
            throw new ForbiddenException("Solo el project manager puede crear vistas compartidas");
        }
        User owner = lookup.user(userId);

        SavedView view = new SavedView();
        view.setProject(project);
        view.setOwner(owner);
        view.setName(request.name().trim());
        view.setShared(shared);
        view.setFilters(normalize(request.filters()));
        view.setPosition(group(project, shared, owner).size());
        return toResponse(viewRepository.save(view), userId);
    }

    @Override
    @Transactional
    public SavedViewResponse update(String slug, UUID projectId, UUID viewId, UUID userId,
                                    UpdateSavedViewRequest request) {
        Project project = lookup.project(slug, projectId);
        SavedView view = findVisibleView(project, viewId, userId);
        ensureCanManage(project, view, userId, "editar");
        boolean share = Boolean.TRUE.equals(request.shared()) && !view.isShared();
        if (share && !projectAccess.isManager(project.getWorkspace(), userId)) {
            throw new ForbiddenException("Solo el project manager puede compartir vistas");
        }

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            view.setName(name);
        }
        if (request.filters() != null) {
            view.setFilters(normalize(request.filters()));
        }
        if (request.shared() != null && request.shared() != view.isShared()) {
            // Pasa al final del otro grupo; el que deja se renumera.
            List<SavedView> previousGroup = without(group(project, view.isShared(), view.getOwner()), view);
            List<SavedView> newGroup = without(group(project, request.shared(), view.getOwner()), view);
            renumber(previousGroup);
            renumber(newGroup);
            view.setShared(request.shared());
            view.setPosition(newGroup.size());
        }
        if (request.position() != null) {
            List<SavedView> ordered = without(group(project, view.isShared(), view.getOwner()), view);
            ordered.add(Math.min(request.position(), ordered.size()), view);
            renumber(ordered);
        }
        // El flush ejecuta @PreUpdate para que la respuesta lleve el updatedAt nuevo.
        return toResponse(viewRepository.saveAndFlush(view), userId);
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID viewId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        SavedView view = findVisibleView(project, viewId, userId);
        ensureCanManage(project, view, userId, "eliminar");
        viewRepository.delete(view);
        renumber(without(group(project, view.isShared(), view.getOwner()), view));
    }

    /** Una vista personal de otro usuario no existe para quien la pide. */
    private SavedView findVisibleView(Project project, UUID viewId, UUID userId) {
        return viewRepository.findByIdAndProjectId(viewId, project.getId())
                .filter(view -> view.isShared() || view.getOwner().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Vista no encontrada"));
    }

    /** Las compartidas solo las gestiona el project manager; las personales, su dueño (ya filtrado al buscarla). */
    private void ensureCanManage(Project project, SavedView view, UUID userId, String action) {
        if (view.isShared() && !projectAccess.isManager(project.getWorkspace(), userId)) {
            throw new ForbiddenException("Solo el project manager puede " + action + " las vistas compartidas");
        }
    }

    /** Las vistas compartidas del proyecto o las personales del dueño, en orden. */
    private List<SavedView> group(Project project, boolean shared, User owner) {
        return shared
                ? viewRepository.findAllByProjectIdAndSharedTrueOrderByPositionAscCreatedAtAsc(project.getId())
                : viewRepository.findAllByProjectIdAndOwnerIdAndSharedFalseOrderByPositionAscCreatedAtAsc(
                        project.getId(), owner.getId());
    }

    private static List<SavedView> without(List<SavedView> views, SavedView excluded) {
        List<SavedView> result = new ArrayList<>(views);
        result.removeIf(view -> view.getId() != null && view.getId().equals(excluded.getId()));
        return result;
    }

    private static void renumber(List<SavedView> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
    }

    private static SavedViewFilters normalize(SavedViewFilters filters) {
        return filters == null ? SavedViewFilters.empty() : filters.normalized();
    }

    private SavedViewResponse toResponse(SavedView view, UUID userId) {
        return projectMapper.toSavedViewResponse(view, view.getOwner().getId().equals(userId));
    }
}
