package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateSavedViewRequest;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.ReorderSavedViewsRequest;
import com.taskflow.api.dto.request.UpdateSavedViewRequest;
import com.taskflow.api.dto.response.SavedViewResponse;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.SavedView;
import com.taskflow.api.entity.SavedViewFilters;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueSpecifications;
import com.taskflow.api.repository.SavedViewRepository;
import com.taskflow.api.service.SavedViewService;
import com.taskflow.api.util.HumanNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SavedViewServiceImpl implements SavedViewService {

    static final String REORDER_SHARED_FORBIDDEN = "Solo el project manager puede ordenar las vistas compartidas";
    static final String REORDER_EMPTY = "Indica el nuevo orden de las vistas compartidas o de tus vistas personales";
    static final String DUPLICATE_SHARED = "Ya hay una vista compartida llamada «%s» en este proyecto";
    static final String DUPLICATE_PERSONAL = "Ya tienes una vista personal llamada «%s» en este proyecto";
    /** Un project manager deja de compartir la vista de otra persona: la vista vuelve a las personales de su dueño. */
    static final String DUPLICATE_OWNER_PERSONAL = "%s ya tiene una vista personal llamada «%s» en este proyecto";

    private final SavedViewRepository viewRepository;
    private final IssueRepository issueRepository;
    private final ProjectAccess projectAccess;
    private final ResourceLookup lookup;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<SavedViewResponse> getViews(String slug, UUID projectId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        return visible(project, userId);
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
        lookup.lockProject(project);
        String name = HumanNames.required(request.name(), "name");
        List<SavedView> group = group(project, shared, owner);
        ensureNameAvailable(group, null, name, shared, null);

        SavedView view = new SavedView();
        view.setProject(project);
        view.setOwner(owner);
        view.setName(name);
        view.setShared(shared);
        view.setFilters(normalize(request.filters()));
        view.setPosition(group.size());
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

        String name = request.name() == null ? view.getName() : HumanNames.required(request.name(), "name");
        boolean shared = request.shared() == null ? view.isShared() : request.shared();
        // Un nombre nuevo o un cambio de grupo (compartir o dejar de compartir) no puede repetir otro del grupo destino
        if (!name.equals(view.getName()) || shared != view.isShared()) {
            lookup.lockProject(project);
            ensureNameAvailable(group(project, shared, view.getOwner()), view, name, shared,
                    view.getOwner().getId().equals(userId) ? null : view.getOwner());
        }
        view.setName(name);
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

    @Override
    @Transactional
    public List<SavedViewResponse> reorder(String slug, UUID projectId, UUID userId,
                                           ReorderSavedViewsRequest request) {
        Project project = lookup.project(slug, projectId);
        if (request.shared() == null && request.personal() == null) {
            throw new BadRequestException(REORDER_EMPTY);
        }
        if (request.shared() != null) {
            if (!projectAccess.isManager(project.getWorkspace(), userId)) {
                throw new ForbiddenException(REORDER_SHARED_FORBIDDEN);
            }
            applyOrder(viewRepository.findAllByProjectIdAndSharedTrueOrderByPositionAscCreatedAtAsc(project.getId()),
                    request.shared(), "las vistas compartidas del proyecto");
        }
        if (request.personal() != null) {
            applyOrder(viewRepository.findAllByProjectIdAndOwnerIdAndSharedFalseOrderByPositionAscCreatedAtAsc(
                    project.getId(), userId), request.personal(), "tus vistas personales");
        }
        viewRepository.flush();
        // Reordenar no cambia los filtros: sin issueCount (null), para no repetir un conteo por vista en cada
        // arrastre. El listado (GET) los trae.
        return viewRepository.findVisible(project.getId(), userId).stream()
                .map(view -> toResponse(view, userId, false))
                .toList();
    }

    /**
     * Las mismas reglas que el listado de tareas ({@code GET /issues}) con los filtros de la vista. Sin tipos, los que
     * muestran el tablero y la lista por defecto (tareas y bugs); con los tres, todos.
     */
    static IssueFilter issueFilter(SavedViewFilters filters) {
        SavedViewFilters f = filters == null ? SavedViewFilters.empty() : filters;
        List<IssueType> types = f.type() == null || f.type().isEmpty() ? SavedViewFilters.DEFAULT_TYPES : f.type();
        return new IssueFilter(f.state(), f.priority(), f.assignee(), f.label(), f.search(), f.cycle(), f.module(),
                f.blocked(), types);
    }

    /**
     * Pone las vistas del grupo en el orden de {@code ids}, que debe tener exactamente las mismas vistas, sin repetir.
     */
    private static void applyOrder(List<SavedView> group, List<UUID> ids, String groupName) {
        Set<UUID> requested = new HashSet<>(ids);
        Map<UUID, SavedView> byId = group.stream().collect(Collectors.toMap(SavedView::getId, Function.identity()));
        if (requested.size() != ids.size() || !requested.equals(byId.keySet())) {
            throw new BadRequestException("La lista debe tener exactamente " + groupName + ", sin repetir");
        }
        renumber(ids.stream().map(byId::get).toList());
    }

    private List<SavedViewResponse> visible(Project project, UUID userId) {
        List<SavedView> views = viewRepository.findVisible(project.getId(), userId);
        FilterTargets targets = targetsFor(project, views);
        return views.stream()
                .map(view -> toResponse(view, userId, targets))
                .toList();
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

    /**
     * Dentro de un grupo (las compartidas del proyecto o las personales de su dueño) no se repiten nombres, sin
     * importar mayúsculas ni espacios de más. {@code self}: la vista que se edita (puede conservar su nombre).
     */
    private static void ensureNameAvailable(List<SavedView> group, SavedView self, String name, boolean shared,
                                            User otherOwner) {
        String key = nameKey(name);
        boolean taken = group.stream()
                .filter(other -> self == null || !other.getId().equals(self.getId()))
                .anyMatch(other -> nameKey(other.getName()).equals(key));
        if (taken) {
            String message = shared ? String.format(DUPLICATE_SHARED, name)
                    : otherOwner != null ? String.format(DUPLICATE_OWNER_PERSONAL, otherOwner.getFullName(), name)
                    : String.format(DUPLICATE_PERSONAL, name);
            throw new ConflictException(message,
                    ConflictException.NAME_TAKEN);
        }
    }

    private static String nameKey(String name) {
        return HumanNames.clean(name).toLowerCase(Locale.ROOT);
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

    /** {@code issueCount}: una consulta de conteo por vista, con los mismos filtros que el listado de tareas. */
    private SavedViewResponse toResponse(SavedView view, UUID userId) {
        return toResponse(view, userId, targetsFor(view.getProject(), List.of(view)));
    }

    private SavedViewResponse toResponse(SavedView view, UUID userId, FilterTargets targets) {
        long issueCount = issueRepository.count(IssueSpecifications.matching(view.getProject().getId(),
                issueFilter(withoutUnknown(view.getFilters(), targets))));
        return projectMapper.toSavedViewResponse(view, view.getOwner().getId().equals(userId), issueCount);
    }

    private SavedViewResponse toResponse(SavedView view, UUID userId, boolean withCount) {
        return withCount ? toResponse(view, userId)
                : projectMapper.toSavedViewResponse(view, view.getOwner().getId().equals(userId), null);
    }

    /**
     * Lo que existe en el proyecto para los filtros de ids de esas vistas; {@code null} (sin consultar) si ninguna
     * filtra por ids.
     */
    private FilterTargets targetsFor(Project project, List<SavedView> views) {
        boolean anyIds = views.stream().map(SavedView::getFilters).anyMatch(FilterTargets::referencesIds);
        return anyIds ? FilterTargets.of(viewRepository.findFilterTargets(project.getId())) : null;
    }

    /**
     * Los filtros sin los ids que ya no existen en el proyecto (una etiqueta, un estado, un ciclo o un módulo borrados,
     * alguien que dejó el proyecto), igual que hace el frontend al abrir la vista. Antes el conteo los usaba: una vista
     * con una etiqueta borrada mostraba 0 tareas en la barra y, al abrirla, todas las del filtro que quedaba.
     * {@code targets} {@code null}: no hay ids que mirar.
     */
    static SavedViewFilters withoutUnknown(SavedViewFilters filters, FilterTargets targets) {
        if (filters == null || targets == null) {
            return filters;
        }
        return new SavedViewFilters(targets.known("state", filters.state()), filters.priority(),
                targets.known("assignee", filters.assignee()), targets.known("label", filters.label()),
                filters.search(), filters.view(), filters.sort(), targets.knownOne("cycle", filters.cycle()),
                targets.knownOne("module", filters.module()), filters.blocked(), filters.groupBy(), filters.type());
    }

    /** Ids que existen en el proyecto, por tipo de filtro ({@link SavedViewRepository#findFilterTargets}). */
    record FilterTargets(Map<String, Set<UUID>> byKind) {

        static FilterTargets of(List<Object[]> rows) {
            Map<String, Set<UUID>> byKind = new HashMap<>();
            for (Object[] row : rows) {
                byKind.computeIfAbsent((String) row[0], kind -> new HashSet<>()).add((UUID) row[1]);
            }
            return new FilterTargets(byKind);
        }

        static boolean referencesIds(SavedViewFilters filters) {
            return filters != null && (notEmpty(filters.state()) || notEmpty(filters.assignee())
                    || notEmpty(filters.label()) || filters.cycle() != null || filters.module() != null);
        }

        List<UUID> known(String kind, List<UUID> ids) {
            if (ids == null) {
                return null;
            }
            Set<UUID> existing = byKind.getOrDefault(kind, Set.of());
            return ids.stream().filter(existing::contains).toList();
        }

        UUID knownOne(String kind, UUID id) {
            return id != null && byKind.getOrDefault(kind, Set.of()).contains(id) ? id : null;
        }

        private static boolean notEmpty(List<UUID> ids) {
            return ids != null && !ids.isEmpty();
        }
    }
}
