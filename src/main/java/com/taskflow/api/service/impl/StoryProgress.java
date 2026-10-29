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
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.util.SortOrderCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * El avance automático de una historia, que sigue a sus tareas:
 * <ul>
 *   <li>Cuando empieza una de sus tareas (entra a un estado STARTED) y la historia sigue en Backlog o Todo, TaskFlow
 *       la mueve al primer estado STARTED del proyecto y lo anota en su historial sin autor
 *       ({@code STORY_AUTO_STATE}: «TaskFlow movió la historia a In Progress · Luis empezó WEB-41»).</li>
 *   <li>Cuando queda «lista para revisar» ({@link StoryRules#isReady}) avisa una vez a los project managers
 *       ({@code STORY_READY}). Si deja de estarlo (se reabre una tarea, llega una tarea nueva o un bug abierto), la
 *       marca se borra y la próxima vez que quede lista vuelve a avisar.</li>
 * </ul>
 * Lo llama {@code IssueServiceImpl} dentro de su transacción, después de cada cambio que afecta a las hijas de una
 * historia (crear, mover, cambiar de padre o de tipo, eliminar).
 */
@Component
@RequiredArgsConstructor
class StoryProgress {

    private final IssueRepository issueRepository;
    private final StateRepository stateRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    /**
     * Cambió una tarea o un bug: revisa su historia actual y, si cambió de padre, también la anterior.
     *
     * @param previousParent el padre antes del cambio ({@code null} si no tenía)
     * @param started        la tarea acaba de empezar: entró a un estado STARTED, o llegó a la historia (o pasó a
     *                       ser {@code TASK}) estando en uno
     */
    void childChanged(Issue child, Issue previousParent, User actor, boolean started) {
        Issue parent = child.getParent();
        reconcile(parent, actor, child, started);
        if (previousParent != null && (parent == null || !Objects.equals(previousParent.getId(), parent.getId()))) {
            reconcile(previousParent, actor, null, false);
        }
    }

    /**
     * Pone al día una historia: la mueve a «en curso» si empezó una de sus tareas y avisa si quedó lista para
     * revisar. Si {@code story} no es una historia, no hace nada.
     *
     * @param trigger la tarea que cambió ({@code null} si se fue o se eliminó)
     */
    void reconcile(Issue story, User actor, Issue trigger, boolean started) {
        if (story == null || !story.isStory()) {
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

    private void start(Issue story, State target, User actor, Issue trigger) {
        story.setState(target);
        story.setSortOrder(SortOrderCalculator.endOfColumn(issueRepository.findMaxSortOrder(target.getId())));
        IssueServiceImpl.updateStartedAt(story, Instant.now());
        // Sin autor: lo hizo TaskFlow. Quién lo provocó va en el texto.
        activityService.record(story, null, ActivityField.STORY_AUTO_STATE, StoryRules.startedBy(actor, trigger),
                target.getName());
    }
}
