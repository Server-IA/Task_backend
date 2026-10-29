package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.StoryChildCount;
import com.taskflow.api.repository.StoryChildCountRow;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.util.SortOrderCalculator;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * El avance automático de una historia, que sigue a sus tareas:
 * <ul>
 *   <li>Cuando empieza una de sus tareas (entra a un estado STARTED, o pasa de Backlog o Todo directo a COMPLETED:
 *       {@link StoryRules#startsStory}) y la historia sigue en Backlog o Todo, TaskFlow la mueve al primer estado
 *       STARTED del proyecto y lo anota en su historial sin autor ({@code STORY_AUTO_STATE}: «TaskFlow movió la
 *       historia a In Progress · Luis empezó WEB-41», o «· Luis terminó WEB-41»).</li>
 *   <li>Cuando queda «lista para revisar» ({@link StoryRules#isReady}) avisa una vez a los project managers
 *       ({@code STORY_READY}). Si deja de estarlo (se reabre una tarea, llega una tarea nueva o un bug abierto), la
 *       marca se borra y la próxima vez que quede lista vuelve a avisar.</li>
 * </ul>
 * Lo llama {@code IssueServiceImpl} dentro de su transacción, después de cada cambio que afecta a las hijas de una
 * historia (crear, mover, cambiar de padre o de tipo, eliminar), y {@code StateServiceImpl} cuando un estado cambia de
 * grupo ({@link #stateGroupChanged}).
 * <p>
 * Orden de los bloqueos: primero la tarea que cambia y después sus historias (todas juntas, {@link #lockStories}).
 */
@Component
@RequiredArgsConstructor
class StoryProgress {

    private final IssueRepository issueRepository;
    private final StateRepository stateRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final EntityManager entityManager;

    /**
     * Cambió una tarea o un bug: revisa su historia actual y, si cambió de padre, también la anterior.
     *
     * @param previousParent el padre antes del cambio ({@code null} si no tenía)
     * @param started        la tarea acaba de empezar ({@link StoryRules#startsStory}): entró a un estado STARTED, pasó
     *                       de Backlog o Todo directo a COMPLETED, o llegó a la historia (o pasó a ser {@code TASK})
     *                       estando en STARTED o COMPLETED
     */
    void childChanged(Issue child, Issue previousParent, User actor, boolean started) {
        Issue parent = child.getParent();
        boolean leftPrevious = previousParent != null
                && (parent == null || !Objects.equals(previousParent.getId(), parent.getId()));
        if (leftPrevious && parent != null) {
            // Las dos historias juntas y en el orden de la base de datos (lockStories). IssueServiceImpl.update ya las
            // bloqueó así antes de cambiar el padre; aquí no vuelve a esperar.
            lockStories(parent, previousParent);
        }
        reconcile(parent, actor, child, started);
        if (leftPrevious) {
            reconcile(previousParent, actor, null, false);
        }
    }

    /**
     * Bloquea las historias indicadas (las nulas se ignoran) en una consulta y siempre en el mismo orden, el de sus ids
     * en PostgreSQL: dos tareas que se cruzan de historia a la vez (A de P1 a P2 y B de P2 a P1) piden P1 y P2 en el
     * mismo orden, y la segunda espera a la primera en vez de bloquearse mutuamente. Se llama antes de guardar el
     * cambio de padre.
     * <p>
     * Que la fila de la tarea ya apunte a la historia nueva no estorba: el {@code FOR KEY SHARE} de la clave foránea
     * no choca con el {@code FOR NO KEY UPDATE} de {@link IssueRepository#lockById}.
     */
    void lockStories(Issue... stories) {
        List<UUID> ids = Stream.of(stories).filter(Objects::nonNull).map(Issue::getId).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        entityManager.flush();
        issueRepository.lockAllById(ids);
    }

    /**
     * Pone al día una historia: la mueve a «en curso» si empezó una de sus tareas y avisa si quedó lista para
     * revisar. Si {@code story} no es una historia, no hace nada.
     * <p>
     * Antes de contar sus hijas bloquea la fila de la historia y la vuelve a leer: dos tareas que terminan a la vez
     * se cuentan en fila (la segunda ve a la primera ya terminada y avisa), y el avance automático no pisa un cierre
     * o una reapertura que el project manager acaba de guardar.
     *
     * @param trigger la tarea que cambió ({@code null} si se fue o se eliminó)
     */
    void reconcile(Issue story, User actor, Issue trigger, boolean started) {
        if (story == null || !story.isStory()) {
            return;
        }
        lock(story);
        if (!story.isStory()) {
            return;
        }
        if (started && trigger != null && trigger.getType() == IssueType.TASK
                && StoryRules.startsWithChild(story.getState().getStateGroup())) {
            stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(story.getProject().getId(),
                    StateGroup.STARTED).ifPresent(target -> start(story, target, actor, trigger));
        }
        boolean ready = StoryRules.isReady(story.getState().getStateGroup(),
                issueRepository.countChildrenOf(story.getId()));
        if (ready && !story.isReviewNotified()) {
            story.setReviewNotified(true);
            notificationService.notifyStoryReady(story,
                    workspaceMemberRepository.findUsersWithRole(story.getProject().getWorkspace().getId(),
                            WorkspaceRole.OWNER),
                    actor);
        } else if (!ready && story.isReviewNotified()) {
            story.setReviewNotified(false);
        }
    }

    /**
     * Un estado cambió de grupo ({@code previous} → el de {@code state}, ya guardado): pone al día, en bloque, las
     * historias que están en él y las de sus tareas, como si cada tarea se hubiera movido a un estado de ese grupo:
     * <ul>
     *   <li>si el estado pasa a STARTED, o de Backlog o Todo a COMPLETED ({@link StoryRules#startsStory}), las
     *       historias en Backlog o Todo de sus tareas pasan a «en curso»;</li>
     *   <li>las historias que este cambio reabre no avisan aunque ya estén listas para revisar (como al reabrir una
     *       historia: quien lo hizo lo sabe); las demás avisan si quedaron listas.</li>
     * </ul>
     * Bloquea todas las historias en una consulta (en el orden de {@link #lockStories}), las lee después de
     * bloquearlas y cuenta las hijas de todas en otra.
     */
    void stateGroupChanged(State state, StateGroup previous, User actor) {
        List<UUID> storyIds = issueRepository.findStoryIdsAffectedByState(state.getId());
        if (storyIds.isEmpty()) {
            return;
        }
        entityManager.flush();
        issueRepository.lockAllById(storyIds);
        // Ninguna estaba cargada en esta transacción (los UPDATE masivos del estado no pasan por las entidades), así que
        // se leen ya bloqueadas y al día
        List<Issue> stories = issueRepository.findAllWithStateByIdIn(storyIds);
        Map<UUID, List<StoryChildCount>> children = new HashMap<>();
        for (StoryChildCountRow row : issueRepository.countChildrenOfAll(storyIds)) {
            children.computeIfAbsent(row.storyId(), id -> new ArrayList<>()).add(row.toCount());
        }
        StateGroup next = state.getStateGroup();
        boolean reopensStories = !previous.isOpen() && next.isOpen();
        Map<UUID, Issue> starters = new HashMap<>();
        State startTarget = null;
        if (StoryRules.startsStory(previous, next)) {
            issueRepository.findTasksWithParentInState(state.getId())
                    .forEach(task -> starters.putIfAbsent(task.getParent().getId(), task));
            startTarget = stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(
                    state.getProject().getId(), StateGroup.STARTED).orElse(null);
        }
        Double maxSortOrder = null;
        List<User> managers = null;
        Instant now = Instant.now();
        for (Issue story : stories) {
            if (!story.isStory()) {
                continue;
            }
            Issue starter = starters.get(story.getId());
            if (starter != null && startTarget != null && StoryRules.startsWithChild(story.getState().getStateGroup())) {
                if (maxSortOrder == null) {
                    maxSortOrder = issueRepository.findMaxSortOrder(startTarget.getId());
                }
                maxSortOrder = SortOrderCalculator.endOfColumn(maxSortOrder);
                start(story, startTarget, actor, starter, maxSortOrder, now);
            }
            boolean ready = StoryRules.isReady(story.getState().getStateGroup(),
                    children.getOrDefault(story.getId(), List.of()));
            if (reopensStories && story.getState().getId().equals(state.getId())) {
                story.setReviewNotified(ready);
            } else if (ready && !story.isReviewNotified()) {
                story.setReviewNotified(true);
                if (managers == null) {
                    managers = workspaceMemberRepository.findUsersWithRole(
                            story.getProject().getWorkspace().getId(), WorkspaceRole.OWNER);
                }
                notificationService.notifyStoryReady(story, managers, actor);
            } else if (!ready && story.isReviewNotified()) {
                story.setReviewNotified(false);
            }
        }
    }

    /**
     * Bloquea la fila de la historia ({@link IssueRepository#lockById}) y la vuelve a leer. Antes escribe lo pendiente
     * de esta transacción, para no perderlo al releerla.
     */
    void lock(Issue story) {
        entityManager.flush();
        issueRepository.lockById(story.getId());
        entityManager.refresh(story);
    }

    private void start(Issue story, State target, User actor, Issue trigger) {
        start(story, target, actor, trigger,
                SortOrderCalculator.endOfColumn(issueRepository.findMaxSortOrder(target.getId())), Instant.now());
    }

    private void start(Issue story, State target, User actor, Issue trigger, double sortOrder, Instant now) {
        story.setState(target);
        story.setSortOrder(sortOrder);
        IssueServiceImpl.updateStartedAt(story, now);
        // Sin autor: lo hizo TaskFlow. Quién lo provocó va en el texto.
        activityService.record(story, null, ActivityField.STORY_AUTO_STATE, StoryRules.startedBy(actor, trigger),
                target.getName());
    }
}
