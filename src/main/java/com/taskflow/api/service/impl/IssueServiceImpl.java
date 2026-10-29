package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateIssueRelationRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.IssueRelationKind;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.SplitItemRequest;
import com.taskflow.api.dto.request.SplitStoryRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueChecklistItem;
import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.DescriptionConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueSpecifications;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.IssueChecklistService;
import com.taskflow.api.service.IssueRelationService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.util.DescriptionTasks;
import com.taskflow.api.util.IssueListSort;
import com.taskflow.api.util.MentionParser;
import com.taskflow.api.util.SortOrderCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IssueServiceImpl implements IssueService {

    /** Límite al recorrer la cadena de padres (protege de datos corruptos). */
    private static final int MAX_PARENT_DEPTH = 100;

    static final String DELETE_FORBIDDEN_MESSAGE = "Solo el project manager o quien creó la tarea puede eliminarla";

    private final IssueRepository issueRepository;
    private final IssueTemplateRepository templateRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final StateRepository stateRepository;
    private final LabelRepository labelRepository;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final IssueRelationService relationService;
    private final IssueChecklistService checklistService;
    private final StoryProgress storyProgress;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<IssueResponse> getIssues(String slug, UUID projectId, IssueFilter filter, Pageable pageable) {
        Project project = lookup.project(slug, projectId);
        Page<Issue> page = issueRepository.findAll(IssueSpecifications.matching(project.getId(), filter),
                IssueListSort.withTieBreakers(pageable));
        return PageResponse.from(page, issueMapper::toResponse);
    }

    @Override
    @Transactional
    public IssueResponse create(String slug, UUID projectId, UUID userId, CreateIssueRequest request) {
        Project project = lookup.project(slug, projectId);
        IssueType type = request.type() != null ? request.type() : IssueType.TASK;
        if (type == IssueType.STORY && !isManager(slug, userId)) {
            throw new ForbiddenException(StoryRules.CREATE_FORBIDDEN);
        }
        State state = request.stateId() != null
                ? findState(project, request.stateId())
                : stateRepository.findFirstByProjectIdOrderByPositionAsc(project.getId())
                        .orElseThrow(() -> new ConflictException("El proyecto no tiene estados"));
        validateDates(request.startDate(), request.dueDate());
        if (request.templateId() != null
                && templateRepository.incrementUsage(request.templateId(), project.getId()) == 0) {
            throw new BadRequestException("La plantilla no existe en este proyecto");
        }

        Instant now = Instant.now();
        User actor = lookup.user(userId);
        Issue issue = new Issue();
        issue.setProject(project);
        issue.setType(type);
        issue.setTitle(request.title().trim());
        issue.setDescription(blankToNull(request.description()));
        issue.setState(state);
        updateCompletedAt(issue, null, now);
        updateStartedAt(issue, now);
        StoryRules.updateClosed(issue, null, actor, now);
        if (request.checklist() != null && !request.checklist().isEmpty()) {
            issue.setChecklistUpdatedAt(now);
        }
        issue.setPriority(request.priority() != null ? request.priority() : IssuePriority.NONE);
        if (request.parentId() != null) {
            Issue parent = findReferencedIssue(project, request.parentId(), "La tarea padre no existe en este proyecto");
            checkNewParent(slug, userId, actor, type, parent, Boolean.TRUE.equals(request.reopenParent()));
            issue.setParent(parent);
        }
        issue.setStartDate(request.startDate());
        issue.setDueDate(request.dueDate());
        issue.getAssignees().addAll(resolveAssignees(project, request.assigneeIds()));
        issue.getLabels().addAll(resolveLabels(project, request.labelIds()));
        issue.setCreatedBy(actor);
        if (issue.getDescription() != null) {
            issue.setDescriptionUpdatedAt(now);
            issue.setDescriptionUpdatedBy(actor);
        }
        // Primero el contador: su UPDATE bloquea la fila del proyecto y así las creaciones simultáneas
        // se ejecutan en fila y no leen el mismo «final de la columna».
        issue.setSequenceId(nextSequenceId(project));
        issue.setSortOrder(SortOrderCalculator.endOfColumn(issueRepository.findMaxSortOrder(state.getId())));
        issueRepository.save(issue);
        setChecklistCounts(issue, checklistService.createAll(issue, request.checklist()));

        activityService.record(issue, actor, ActivityField.CREATED, null, issue.getTitle());
        notificationService.notifyAssigned(issue, actor, issue.getAssignees());
        notificationService.notifyMentionedInDescription(issue, actor,
                mentionable(project, actor, MentionParser.userIds(issue.getDescription())));
        if (request.relatesToId() != null) {
            relationService.create(slug, projectId, issue.getId(), userId,
                    new CreateIssueRelationRequest(IssueRelationKind.RELATES, request.relatesToId()));
        }
        if (issue.getParent() != null) {
            storyProgress.childChanged(issue, null, actor, StoryRules.startsStory(null, state.getStateGroup()));
        }
        return issueMapper.toResponse(issue);
    }

    @Override
    @Transactional(readOnly = true)
    public IssueResponse get(String slug, UUID projectId, UUID issueId) {
        Project project = lookup.project(slug, projectId);
        return issueMapper.toResponse(findIssue(project, issueId));
    }

    @Override
    @Transactional
    public IssueResponse update(String slug, UUID projectId, UUID issueId, UUID userId, UpdateIssueRequest request) {
        Project project = lookup.project(slug, projectId);
        // Antes de leerla: los cambios de la misma tarea van en fila (la versión de la descripción no se salta y nadie
        // guarda una copia vieja de la tarea)
        issueRepository.lockById(issueId);
        Issue issue = findIssue(project, issueId);
        String description = blankToNull(request.description());
        boolean descriptionChanged = request.description() != null
                && !Objects.equals(issue.getDescription(), description);
        if (descriptionChanged && request.baseDescriptionVersion() != null
                && request.baseDescriptionVersion() != issue.getDescriptionVersion()) {
            // Alguien la cambió después de la versión de la que partió quien guarda: no se pisa (no cambia nada)
            throw new DescriptionConflictException(issueMapper.toResponse(issue));
        }
        User actor = lookup.user(userId);
        IssueChanges changes = new IssueChanges(issue, actor);
        boolean completed = false;
        // Lo que puede mover a su historia: cambiar de estado, de padre o de tipo
        Issue previousParent = issue.getParent();
        StateGroup originalGroup = issue.getState().getStateGroup();
        boolean storyRelevant = false;
        boolean started = false;
        boolean typeChanged = false;
        // La regla de la historia cerrada ya se aplicó al padre nuevo (checkNewParent)
        boolean parentChecked = false;

        if (request.type() != null && request.type() != issue.getType()) {
            changeType(slug, userId, project, issue, request.type(), actor, changes, request.description() == null);
            typeChanged = true;
            storyRelevant = true;
            started = request.type() == IssueType.TASK
                    && StoryRules.startsStory(null, issue.getState().getStateGroup());
        }

        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new BadRequestException("El título no puede estar vacío");
            }
            String title = request.title().trim();
            changes.track(ActivityField.TITLE, issue.getTitle(), title);
            issue.setTitle(title);
        }
        if (descriptionChanged) {
            recordDescriptionChange(project, issue, actor, description);
            issue.setDescription(description);
            issue.setDescriptionVersion(issue.getDescriptionVersion() + 1);
            issue.setDescriptionUpdatedAt(Instant.now());
            issue.setDescriptionUpdatedBy(actor);
        }
        if (request.stateId() != null && !request.stateId().equals(issue.getState().getId())) {
            State state = findState(project, request.stateId());
            StateGroup previousGroup = issue.getState().getStateGroup();
            ensureStoryStateChangeAllowed(slug, userId, issue, state);
            changes.track(ActivityField.STATE, issue.getState().getName(), state.getName());
            issue.setState(state);
            Instant now = Instant.now();
            updateCompletedAt(issue, previousGroup, now);
            updateStartedAt(issue, now);
            StoryRules.updateClosed(issue, previousGroup, actor, now);
            completed = becameCompleted(previousGroup, state.getStateGroup());
            issue.setSortOrder(SortOrderCalculator.endOfColumn(issueRepository.findMaxSortOrder(state.getId())));
            storyRelevant = true;
            started |= StoryRules.startsStory(previousGroup, state.getStateGroup());
        }
        if (request.priority() != null) {
            changes.track(ActivityField.PRIORITY, issue.getPriority().name(), request.priority().name());
            issue.setPriority(request.priority());
        }
        if (Boolean.TRUE.equals(request.clearParent())) {
            changes.track(ActivityField.PARENT, codeOf(issue.getParent()), null);
            issue.setParent(null);
            storyRelevant = true;
        } else if (request.parentId() != null) {
            Issue parent = findReferencedIssue(project, request.parentId(), "La tarea padre no existe en este proyecto");
            ensureNoCycle(issue, parent);
            boolean newParent = issue.getParent() == null || !issue.getParent().getId().equals(parent.getId());
            if (newParent) {
                // Antes de guardar el padre nuevo, las dos historias juntas y en orden (StoryProgress.lockStories)
                storyProgress.lockStories(previousParent, parent);
                checkNewParent(slug, userId, actor, issue.getType(), parent,
                        Boolean.TRUE.equals(request.reopenParent()));
                started |= StoryRules.startsStory(null, issue.getState().getStateGroup());
                storyRelevant = true;
                parentChecked = true;
            }
            changes.track(ActivityField.PARENT, codeOf(issue.getParent()), parent.getCode());
            issue.setParent(parent);
        }
        // Solo si esta petición cambia el tipo o el padre: una historia que ya tenía padre (datos viejos) se sigue
        // pudiendo editar
        if (issue.isStory() && issue.getParent() != null && (typeChanged || parentChecked)) {
            throw new BadRequestException(StoryRules.STORY_AS_CHILD);
        }
        if (!parentChecked) {
            // Una tarea de una historia cerrada no vuelve a ser trabajo abierto sin reabrir la historia: ni al pasar
            // a TASK ni al salir de un estado completado o cancelado
            boolean reopenParent = Boolean.TRUE.equals(request.reopenParent());
            if (typeChanged && issue.getType() == IssueType.TASK) {
                requireOpenStory(slug, userId, actor, issue.getParent(), reopenParent,
                        IssueServiceImpl::closedStoryConflict);
            } else {
                checkReopenedWork(slug, userId, actor, issue, originalGroup, issue.getState().getStateGroup(),
                        reopenParent);
            }
        }
        if (Boolean.TRUE.equals(request.clearStartDate())) {
            changes.track(ActivityField.START_DATE, toText(issue.getStartDate()), null);
            issue.setStartDate(null);
        } else if (request.startDate() != null) {
            changes.track(ActivityField.START_DATE, toText(issue.getStartDate()), toText(request.startDate()));
            issue.setStartDate(request.startDate());
        }
        if (Boolean.TRUE.equals(request.clearDueDate())) {
            changes.track(ActivityField.DUE_DATE, toText(issue.getDueDate()), null);
            issue.setDueDate(null);
        } else if (request.dueDate() != null) {
            changes.track(ActivityField.DUE_DATE, toText(issue.getDueDate()), toText(request.dueDate()));
            issue.setDueDate(request.dueDate());
        }
        validateDates(issue.getStartDate(), issue.getDueDate());

        if (request.assigneeIds() != null) {
            Set<User> assignees = resolveAssignees(project, request.assigneeIds());
            Set<User> added = new HashSet<>(assignees);
            added.removeAll(issue.getAssignees());
            changes.track(ActivityField.ASSIGNEES, names(issue.getAssignees(), User::getFullName),
                    names(assignees, User::getFullName));
            issue.getAssignees().clear();
            issue.getAssignees().addAll(assignees);
            notificationService.notifyAssigned(issue, actor, added);
        }
        if (request.labelIds() != null) {
            Set<Label> labels = resolveLabels(project, request.labelIds());
            changes.track(ActivityField.LABELS, names(issue.getLabels(), Label::getName), names(labels, Label::getName));
            issue.getLabels().clear();
            issue.getLabels().addAll(labels);
        }
        // El flush ejecuta @PreUpdate para que la respuesta lleve el updatedAt nuevo.
        Issue saved = issueRepository.saveAndFlush(issue);
        if (completed) {
            relationService.notifyUnblocked(saved, actor);
        }
        if (storyRelevant) {
            storyProgress.childChanged(saved, previousParent, actor, started);
        }
        if (typeChanged && saved.isStory()) {
            // Una tarea con hijas que pasa a historia puede quedar lista para revisar en el acto
            storyProgress.reconcile(saved, actor, null, false);
        }
        return issueMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public IssueResponse move(String slug, UUID projectId, UUID issueId, UUID userId, MoveIssueRequest request) {
        Project project = lookup.project(slug, projectId);
        issueRepository.lockById(issueId);
        Issue issue = findIssue(project, issueId);
        State target = findState(project, request.stateId());
        User actor = null;
        boolean stateChanged = !target.getId().equals(issue.getState().getId());
        if (stateChanged) {
            ensureStoryStateChangeAllowed(slug, userId, issue, target);
            actor = lookup.user(userId);
            checkReopenedWork(slug, userId, actor, issue, issue.getState().getStateGroup(), target.getStateGroup(),
                    Boolean.TRUE.equals(request.reopenParent()));
            activityService.record(issue, actor, ActivityField.STATE, issue.getState().getName(), target.getName());
        }

        Issue above = request.afterIssueId() != null ? findNeighbor(project, target, issue, request.afterIssueId()) : null;
        Issue below = request.beforeIssueId() != null ? findNeighbor(project, target, issue, request.beforeIssueId()) : null;

        if (above != null && below != null) {
            if (above.getSortOrder() >= below.getSortOrder()) {
                throw new BadRequestException("afterIssueId debe estar justo arriba de beforeIssueId en la columna");
            }
            if (SortOrderCalculator.needsRebalance(above.getSortOrder(), below.getSortOrder())) {
                rebalanceColumn(target, issue);
            }
        }

        Double maxInColumn = issueRepository.findMaxSortOrder(target.getId());
        issue.setSortOrder(SortOrderCalculator.between(
                above != null ? above.getSortOrder() : null,
                below != null ? below.getSortOrder() : null,
                maxInColumn));
        StateGroup previousGroup = issue.getState().getStateGroup();
        issue.setState(target);
        Instant now = Instant.now();
        updateCompletedAt(issue, previousGroup, now);
        updateStartedAt(issue, now);
        StoryRules.updateClosed(issue, previousGroup, actor, now);
        Issue saved = issueRepository.saveAndFlush(issue);
        // Pasar de un grupo abierto a COMPLETED implica cambiar de estado, así que actor ya está cargado.
        if (becameCompleted(previousGroup, target.getStateGroup())) {
            relationService.notifyUnblocked(saved, actor);
        }
        if (stateChanged && saved.getParent() != null) {
            storyProgress.childChanged(saved, null, actor,
                    StoryRules.startsStory(previousGroup, target.getStateGroup()));
        }
        return issueMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID issueId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        Issue issue = findIssue(project, issueId);
        if (issue.isStory()) {
            // Una historia (con su historial y sus tareas) solo la elimina el project manager, aunque la haya creado
            // otra persona como tarea
            if (!isManager(slug, userId)) {
                throw new ForbiddenException(StoryRules.DELETE_FORBIDDEN);
            }
        } else {
            boolean isCreator = issue.getCreatedBy() != null && issue.getCreatedBy().getId().equals(userId);
            if (!isCreator && !isManager(slug, userId)) {
                throw new ForbiddenException(DELETE_FORBIDDEN_MESSAGE);
            }
        }
        // Los sub-issues no se borran: la base de datos les quita el padre (ON DELETE SET NULL). Se bloquean antes que
        // la historia, en el mismo orden que quien mueve una de ellas (la tarea y luego su historia)
        if (issue.isStory()) {
            issueRepository.lockChildrenOf(issue.getId());
        }
        Issue parent = issue.getParent();
        issueRepository.delete(issue);
        if (parent != null && parent.isStory()) {
            // Sin esta tarea (o bug), la historia puede quedar lista para revisar
            issueRepository.flush();
            storyProgress.reconcile(parent, lookup.user(userId), null, false);
        }
    }

    @Override
    @Transactional
    public List<IssueResponse> split(String slug, UUID projectId, UUID storyId, UUID userId,
                                     SplitStoryRequest request) {
        Project project = lookup.project(slug, projectId);
        issueRepository.lockById(storyId);
        Issue story = findIssue(project, storyId);
        if (!story.isStory()) {
            throw new BadRequestException("Solo se parten las historias");
        }
        User actor = lookup.user(userId);
        boolean addsTasks = request.items().stream().anyMatch(item -> typeOf(item) == IssueType.TASK);
        if (addsTasks && !story.getState().getStateGroup().isOpen()) {
            if (!Boolean.TRUE.equals(request.reopen())) {
                throw closedStoryConflict(story);
            }
            reopen(story, actor);
        }
        // Las tareas nuevas nacen en «Todo» (el primer estado sin empezar) o, si no hay, en el primer estado
        State state = stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                        StateGroup.UNSTARTED)
                .or(() -> stateRepository.findFirstByProjectIdOrderByPositionAsc(project.getId()))
                .orElseThrow(() -> new ConflictException("El proyecto no tiene estados"));
        // Heredan de la historia su ciclo (si sigue abierto), sus módulos y sus etiquetas
        List<Cycle> cycles = story.getCycles().stream().filter(cycle -> !cycle.isClosed()).toList();
        List<ProjectModule> modules = List.copyOf(story.getModules());
        Instant now = Instant.now();

        List<Issue> created = new ArrayList<>();
        for (SplitItemRequest item : request.items()) {
            IssueType type = typeOf(item);
            if (type == IssueType.STORY) {
                throw new BadRequestException(StoryRules.SPLIT_TYPE);
            }
            Issue child = new Issue();
            child.setProject(project);
            child.setType(type);
            child.setTitle(item.title().trim());
            child.setState(state);
            updateCompletedAt(child, null, now);
            updateStartedAt(child, now);
            child.setPriority(item.priority() != null ? item.priority() : story.getPriority());
            child.setParent(story);
            child.getAssignees().addAll(resolveAssignees(project, item.assigneeIds()));
            child.getLabels().addAll(story.getLabels());
            child.setCreatedBy(actor);
            child.setSequenceId(nextSequenceId(project));
            child.setSortOrder(SortOrderCalculator.endOfColumn(issueRepository.findMaxSortOrder(state.getId())));
            issueRepository.save(child);
            for (Cycle cycle : cycles) {
                cycle.getIssues().add(child);
                child.getCycles().add(cycle);
            }
            for (ProjectModule module : modules) {
                module.getIssues().add(child);
                child.getModules().add(module);
            }
            activityService.record(child, actor, ActivityField.CREATED, null, child.getTitle());
            notificationService.notifyAssigned(child, actor, child.getAssignees());
            created.add(child);
        }
        issueRepository.flush();
        // Si las tareas nacen ya empezadas o completadas (un proyecto sin estados «sin empezar»), la historia empieza
        // con la primera, como al crear una tarea suelta bajo ella
        Issue starter = StoryRules.startsStory(null, state.getStateGroup())
                ? created.stream().filter(child -> child.getType() == IssueType.TASK).findFirst().orElse(null)
                : null;
        storyProgress.reconcile(story, actor, starter, starter != null);
        return created.stream().map(issueMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public IssueResponse closeStory(String slug, UUID projectId, UUID storyId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        issueRepository.lockById(storyId);
        Issue story = findStory(project, storyId);
        if (!story.getState().getStateGroup().isOpen()) {
            throw new ConflictException("La historia ya está cerrada");
        }
        State done = stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                        StateGroup.COMPLETED)
                .orElseThrow(() -> new ConflictException("El proyecto no tiene un estado completado"));
        User actor = lookup.user(userId);
        StateGroup previousGroup = story.getState().getStateGroup();
        changeStoryState(story, done, actor);
        Issue saved = issueRepository.saveAndFlush(story);
        if (becameCompleted(previousGroup, done.getStateGroup())) {
            relationService.notifyUnblocked(saved, actor);
        }
        return issueMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public IssueResponse reopenStory(String slug, UUID projectId, UUID storyId, UUID userId) {
        Project project = lookup.project(slug, projectId);
        issueRepository.lockById(storyId);
        Issue story = findStory(project, storyId);
        if (story.getState().getStateGroup().isOpen()) {
            throw new ConflictException("La historia no está cerrada");
        }
        reopen(story, lookup.user(userId));
        return issueMapper.toResponse(issueRepository.saveAndFlush(story));
    }

    private Issue findStory(Project project, UUID storyId) {
        Issue story = findIssue(project, storyId);
        if (!story.isStory()) {
            throw new BadRequestException("La tarea no es una historia");
        }
        return story;
    }

    private static IssueType typeOf(SplitItemRequest item) {
        return item.type() != null ? item.type() : IssueType.TASK;
    }

    /**
     * Reabre una historia cerrada (solo el project manager llega aquí): vuelve al primer estado STARTED (o, si el
     * proyecto no tiene, al primer estado abierto) y queda en su historial. Si al reabrirla ya está lista para revisar,
     * no se vuelve a avisar (quien la reabre lo sabe); si no lo está, avisará cuando lo esté.
     */
    private void reopen(Issue story, User actor) {
        UUID projectId = story.getProject().getId();
        State target = stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(projectId,
                        StateGroup.STARTED)
                .or(() -> stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(projectId,
                        StateGroup.UNSTARTED))
                .or(() -> stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(projectId,
                        StateGroup.BACKLOG))
                .orElseThrow(() -> new ConflictException("El proyecto no tiene un estado abierto"));
        changeStoryState(story, target, actor);
        story.setReviewNotified(StoryRules.isReady(target.getStateGroup(),
                issueRepository.countChildrenOf(story.getId())));
    }

    /** Cambia el estado de una historia con su historial, sus fechas y quién la cerró. */
    private void changeStoryState(Issue story, State target, User actor) {
        StateGroup previousGroup = story.getState().getStateGroup();
        activityService.record(story, actor, ActivityField.STATE, story.getState().getName(), target.getName());
        story.setState(target);
        Instant now = Instant.now();
        updateCompletedAt(story, previousGroup, now);
        updateStartedAt(story, now);
        StoryRules.updateClosed(story, previousGroup, actor, now);
        story.setSortOrder(SortOrderCalculator.endOfColumn(issueRepository.findMaxSortOrder(target.getId())));
    }

    /**
     * Cerrar o reabrir una historia (entrar a un estado cerrado, salir de uno o moverse entre dos) es solo del project
     * manager, por cualquier camino: el selector de estado, el tablero o la API.
     */
    private void ensureStoryStateChangeAllowed(String slug, UUID userId, Issue issue, State target) {
        if (!issue.isStory()) {
            return;
        }
        String message = StoryRules.managerOnlyMessage(issue.getState().getStateGroup(), target.getStateGroup());
        if (message != null && !isManager(slug, userId)) {
            throw new ForbiddenException(message);
        }
    }

    /**
     * Reglas del padre nuevo de una tarea: una historia no puede ser hija de nada, y agregar una tarea ({@code TASK})
     * a una historia cerrada exige reabrirla ({@code reopenParent}, solo el project manager). Un bug se liga a una
     * historia cerrada sin reabrirla («bug después de cerrar»).
     */
    private void checkNewParent(String slug, UUID userId, User actor, IssueType type, Issue parent, boolean reopen) {
        if (type == IssueType.STORY) {
            throw new BadRequestException(StoryRules.STORY_AS_CHILD);
        }
        if (type == IssueType.TASK) {
            requireOpenStory(slug, userId, actor, parent, reopen, IssueServiceImpl::closedStoryConflict);
        }
    }

    /**
     * Una tarea ({@code TASK}) de una historia cerrada que sale de un estado completado o cancelado a uno abierto
     * vuelve a ser trabajo abierto: exige reabrir la historia, igual que agregarle una tarea.
     */
    private void checkReopenedWork(String slug, UUID userId, User actor, Issue issue, StateGroup from, StateGroup to,
                                   boolean reopen) {
        if (issue.getType() == IssueType.TASK && !from.isOpen() && to.isOpen()) {
            requireOpenStory(slug, userId, actor, issue.getParent(), reopen, IssueServiceImpl::reopenedWorkConflict);
        }
    }

    /**
     * Si {@code story} es una historia cerrada: sin {@code reopen}, 409 {@code STORY_CLOSED}; con él, solo el project
     * manager la reabre (queda en su historial). Si no es una historia o está abierta, no hace nada.
     * <p>
     * Antes de mirar si está abierta, bloquea la historia y la vuelve a leer: un cierre o una reapertura que otra
     * persona guarda a la vez (por ejemplo el {@code /reopen} del project manager) se ve aquí, y la historia no se
     * reabre dos veces.
     */
    private void requireOpenStory(String slug, UUID userId, User actor, Issue story, boolean reopen,
                                  Function<Issue, ConflictException> conflict) {
        if (story == null || !story.isStory()) {
            return;
        }
        storyProgress.lock(story);
        if (!story.isStory() || story.getState().getStateGroup().isOpen()) {
            return;
        }
        if (!reopen) {
            throw conflict.apply(story);
        }
        if (!isManager(slug, userId)) {
            throw new ForbiddenException(StoryRules.REOPEN_FORBIDDEN);
        }
        reopen(story, actor);
    }

    private static ConflictException closedStoryConflict(Issue story) {
        return new ConflictException(story.getCode() + " está cerrada. Reábrela o crea la tarea suelta relacionada",
                ConflictException.STORY_CLOSED);
    }

    private static ConflictException reopenedWorkConflict(Issue story) {
        return new ConflictException(story.getCode() + " está cerrada. Reábrela para volver a abrir esta tarea",
                ConflictException.STORY_CLOSED);
    }

    /**
     * Cambia el tipo. Convertir en historia o dejar de serlo es del project manager; una historia con tareas no puede
     * dejar de serlo. Al pasar a bug con la descripción vacía, se agregan las secciones del reporte.
     *
     * @param mayFillDescription la petición no trae descripción (si la trae, se respeta)
     */
    private void changeType(String slug, UUID userId, Project project, Issue issue, IssueType type, User actor,
                            IssueChanges changes, boolean mayFillDescription) {
        IssueType previous = issue.getType();
        if ((previous == IssueType.STORY || type == IssueType.STORY) && !isManager(slug, userId)) {
            throw new ForbiddenException(StoryRules.TYPE_FORBIDDEN);
        }
        if (previous == IssueType.STORY && issueRepository.existsByParentId(issue.getId())) {
            throw new ConflictException(StoryRules.TYPE_WITH_CHILDREN);
        }
        changes.track(ActivityField.TYPE, previous.name(), type.name());
        issue.setType(type);
        issue.setReviewNotified(false);
        if (previous == IssueType.STORY) {
            issue.setClosedAt(null);
            issue.setClosedBy(null);
        } else if (type == IssueType.STORY) {
            StoryRules.updateClosed(issue, null, actor, Instant.now());
        }
        if (type == IssueType.BUG && mayFillDescription && issue.getDescription() == null) {
            String skeleton = templateRepository
                    .findFirstByProjectIdAndTypeOrderByPositionAscCreatedAtAsc(project.getId(), IssueType.BUG)
                    .map(IssueTemplate::getDescription)
                    .filter(text -> text != null && !text.isBlank())
                    .orElse(DefaultTemplates.BUG_REPORT_DESCRIPTION);
            activityService.recordDescriptionEdit(issue, actor);
            issue.setDescription(skeleton);
            issue.setDescriptionVersion(issue.getDescriptionVersion() + 1);
            issue.setDescriptionUpdatedAt(Instant.now());
            issue.setDescriptionUpdatedBy(actor);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<IssueResponse> getSubIssues(String slug, UUID projectId, UUID issueId) {
        Project project = lookup.project(slug, projectId);
        Issue issue = findIssue(project, issueId);
        return issueRepository.findAllByParentIdOrderBySequenceIdAsc(issue.getId()).stream()
                .map(issueMapper::toResponse)
                .toList();
    }

    /**
     * Mantiene {@code completedAt} tras un cambio de estado: se fija al entrar al grupo COMPLETED y se borra
     * al salir de él. Moverse entre dos estados completados conserva la fecha original.
     *
     * @param previousGroup grupo del estado anterior, o {@code null} si la tarea es nueva
     */
    static void updateCompletedAt(Issue issue, StateGroup previousGroup, Instant now) {
        if (issue.getState().getStateGroup() != StateGroup.COMPLETED) {
            issue.setCompletedAt(null);
        } else if (previousGroup != StateGroup.COMPLETED || issue.getCompletedAt() == null) {
            issue.setCompletedAt(now);
        }
    }

    /**
     * Fija {@code startedAt} la primera vez que la tarea entra al grupo STARTED (también si se crea en él). No se
     * borra al salir: el tiempo de ciclo se mide desde la primera vez que empezó.
     */
    static void updateStartedAt(Issue issue, Instant now) {
        if (issue.getState().getStateGroup() == StateGroup.STARTED && issue.getStartedAt() == null) {
            issue.setStartedAt(now);
        }
    }

    /**
     * Una tarea abierta pasó al grupo COMPLETED: deja de bloquear a otras. Venir de CANCELLED no cuenta porque una
     * tarea cancelada ya no bloqueaba, y moverse entre dos estados completados tampoco.
     */
    static boolean becameCompleted(StateGroup previousGroup, StateGroup currentGroup) {
        return previousGroup != null && previousGroup.isOpen() && currentGroup == StateGroup.COMPLETED;
    }

    /**
     * Las fórmulas de la checklist solo se calculan al leer la tarea de la base de datos; en una tarea recién creada
     * se rellenan con los elementos que se acaban de guardar (ninguno hecho).
     */
    private static void setChecklistCounts(Issue issue, List<IssueChecklistItem> items) {
        issue.setChecklistTotal(countOfKind(items, ChecklistItemKind.CHECK));
        issue.setCriteriaTotal(countOfKind(items, ChecklistItemKind.CRITERION));
        issue.setChecklistDone(0);
        issue.setCriteriaDone(0);
    }

    private static int countOfKind(List<IssueChecklistItem> items, ChecklistItemKind kind) {
        return (int) items.stream().filter(item -> item.getKind() == kind).count();
    }

    private boolean isManager(String slug, UUID userId) {
        return workspaceMemberRepository.findRole(slug, userId)
                .map(WorkspaceRole::isManager)
                .orElse(false);
    }

    /** Incrementa el contador del proyecto en la misma transacción y devuelve el nuevo valor. */
    private int nextSequenceId(Project project) {
        projectRepository.incrementIssueCounter(project.getId());
        return projectRepository.findIssueCounter(project.getId());
    }

    /** Renumera la columna con separaciones uniformes cuando ya no cabe un promedio entre dos tarjetas. */
    private void rebalanceColumn(State state, Issue moving) {
        List<Issue> column = issueRepository.findAllByStateIdOrderBySortOrderAsc(state.getId()).stream()
                .filter(other -> !other.getId().equals(moving.getId()))
                .toList();
        for (int i = 0; i < column.size(); i++) {
            column.get(i).setSortOrder(SortOrderCalculator.positionAt(i));
        }
    }

    private Issue findNeighbor(Project project, State target, Issue moving, UUID neighborId) {
        if (neighborId.equals(moving.getId())) {
            throw new BadRequestException("Una tarea no puede ser vecina de sí misma");
        }
        Issue neighbor = findReferencedIssue(project, neighborId, "La tarea vecina no existe en este proyecto");
        if (!neighbor.getState().getId().equals(target.getId())) {
            throw new BadRequestException("Las tareas vecinas deben estar en el estado destino");
        }
        return neighbor;
    }

    private void ensureNoCycle(Issue issue, Issue newParent) {
        Issue current = newParent;
        for (int depth = 0; current != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (current.getId().equals(issue.getId())) {
                throw new BadRequestException("Una tarea no puede ser padre de sí misma ni de sus ancestros");
            }
            current = current.getParent();
        }
    }

    private Set<User> resolveAssignees(Project project, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<UUID> distinct = new HashSet<>(ids);
        List<User> users = userRepository.findAllById(distinct);
        if (users.size() != distinct.size()
                || projectMemberRepository.countMembers(project.getId(), distinct) != distinct.size()) {
            throw new BadRequestException("Los asignados deben ser miembros del proyecto");
        }
        return new HashSet<>(users);
    }

    private Set<Label> resolveLabels(Project project, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<UUID> distinct = new HashSet<>(ids);
        List<Label> labels = labelRepository.findAllByProjectIdAndIdIn(project.getId(), distinct);
        if (labels.size() != distinct.size()) {
            throw new BadRequestException("Las etiquetas deben pertenecer al proyecto");
        }
        return new HashSet<>(labels);
    }

    /**
     * De los usuarios mencionados, los que pueden ver la tarea (miembros del proyecto o project managers del
     * workspace), sin contar a quien escribió. Los ids de personas ajenas al proyecto se ignoran.
     */
    private List<User> mentionable(Project project, User author, Set<UUID> mentionedIds) {
        Set<UUID> ids = new LinkedHashSet<>(mentionedIds);
        ids.remove(author.getId());
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findMentionable(ids, project.getId(), project.getWorkspace().getId());
    }

    /**
     * Historial y avisos de un cambio de la descripción. Marcar o desmarcar casillas («- [ ] …») deja una entrada por
     * casilla («marcó «…» en la descripción»); cualquier otro cambio, «editó la descripción» (una entrada por sesión
     * de edición, sin el texto). Las personas mencionadas por primera vez reciben el aviso.
     */
    private void recordDescriptionChange(Project project, Issue issue, User actor, String description) {
        DescriptionTasks.Diff tasks = DescriptionTasks.diff(issue.getDescription(), description);
        if (!tasks.onlyToggles()) {
            activityService.recordDescriptionEdit(issue, actor);
        }
        tasks.toggles().forEach(toggle ->
                activityService.recordDescriptionTask(issue, actor, toggle.text(), toggle.checked()));
        Set<UUID> newMentions = new LinkedHashSet<>(MentionParser.userIds(description));
        newMentions.removeAll(MentionParser.userIds(issue.getDescription()));
        notificationService.notifyMentionedInDescription(issue, actor, mentionable(project, actor, newMentions));
    }

    private Issue findIssue(Project project, UUID issueId) {
        return issueRepository.findByIdAndProjectId(issueId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
    }

    /** Issue mencionado en el cuerpo de la petición: si no existe, es un dato inválido (400). */
    private Issue findReferencedIssue(Project project, UUID issueId, String message) {
        return issueRepository.findByIdAndProjectId(issueId, project.getId())
                .orElseThrow(() -> new BadRequestException(message));
    }

    private State findState(Project project, UUID stateId) {
        return stateRepository.findByIdAndProjectId(stateId, project.getId())
                .orElseThrow(() -> new BadRequestException("El estado no pertenece al proyecto"));
    }

    /**
     * Registra en el historial solo los campos cuyo valor realmente cambió.
     */
    private class IssueChanges {

        private final Issue issue;
        private final User actor;

        IssueChanges(Issue issue, User actor) {
            this.issue = issue;
            this.actor = actor;
        }

        void track(ActivityField field, String oldValue, String newValue) {
            if (!Objects.equals(oldValue, newValue)) {
                activityService.record(issue, actor, field, oldValue, newValue);
            }
        }
    }

    /** Nombres ordenados y separados por coma, o {@code null} si no hay ninguno. */
    private static <T> String names(Collection<T> items, Function<T, String> name) {
        if (items.isEmpty()) {
            return null;
        }
        return items.stream().map(name).sorted(Comparator.naturalOrder()).collect(Collectors.joining(", "));
    }

    private static String codeOf(Issue issue) {
        return issue == null ? null : issue.getCode();
    }

    private static String toText(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static void validateDates(LocalDate startDate, LocalDate dueDate) {
        if (startDate != null && dueDate != null && dueDate.isBefore(startDate)) {
            throw new BadRequestException("La fecha límite no puede ser anterior a la fecha de inicio");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
