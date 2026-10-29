package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.repository.StoryChildCount;

import java.time.Instant;
import java.util.Collection;

/**
 * Reglas de las historias que no dependen de la base de datos.
 */
final class StoryRules {

    static final String CLOSE_FORBIDDEN = "Solo el project manager cierra la historia";
    static final String REOPEN_FORBIDDEN = "Solo el project manager reabre la historia";
    static final String CREATE_FORBIDDEN = "Solo el project manager crea historias";
    static final String TYPE_FORBIDDEN = "Solo el project manager convierte una tarea en historia o una historia en tarea";
    static final String TYPE_WITH_CHILDREN = "Una historia con tareas no puede cambiar de tipo hasta mover sus tareas";
    static final String STORY_AS_CHILD = "Una historia no puede ser hija de otra tarea";
    static final String SPLIT_TYPE = "Al partir una historia solo se crean tareas o bugs";
    static final String DELETE_FORBIDDEN = "Solo el project manager elimina historias";

    private StoryRules() {
    }

    /**
     * «Lista para revisar»: la historia está abierta, tiene al menos una tarea completada, todas sus tareas están
     * completadas o canceladas y ningún bug ligado sigue abierto (una historia cuyas tareas se cancelaron todas no está
     * lista: no se hizo nada). La misma regla que {@code Issue.readyForReview}.
     *
     * @param storyGroup grupo del estado de la historia
     * @param children   sus hijas contadas por tipo y grupo
     */
    static boolean isReady(StateGroup storyGroup, Collection<StoryChildCount> children) {
        if (!storyGroup.isOpen()) {
            return false;
        }
        boolean hasTask = false;
        for (StoryChildCount child : children) {
            if (child.count() == 0) {
                continue;
            }
            if (child.type() == IssueType.TASK && child.group() == StateGroup.COMPLETED) {
                hasTask = true;
            }
            boolean counts = child.type() == IssueType.TASK || child.type() == IssueType.BUG;
            if (counts && child.group().isOpen()) {
                return false;
            }
        }
        return hasTask;
    }

    /** Una historia en Backlog o Todo pasa sola a «en curso» cuando empieza la primera de sus tareas. */
    static boolean startsWithChild(StateGroup storyGroup) {
        return storyGroup == StateGroup.BACKLOG || storyGroup == StateGroup.UNSTARTED;
    }

    /**
     * Si una tarea que pasa del grupo {@code from} al grupo {@code to} empieza su historia (la mueve a «en curso» si
     * está en Backlog o Todo, {@link #startsWithChild}):
     * <ul>
     *   <li>entra a STARTED desde otro grupo;</li>
     *   <li>se completa sin pasar por STARTED (de Backlog o Todo directo a COMPLETED): el trabajo se hizo, y una
     *       historia «lista para revisar» no se queda en Backlog o Todo.</li>
     * </ul>
     *
     * @param from grupo anterior, o {@code null} si la tarea llega a la historia ya en {@code to}: se crea, cambia de
     *             historia o pasa a ser {@code TASK}
     */
    static boolean startsStory(StateGroup from, StateGroup to) {
        if (to == StateGroup.STARTED) {
            return from != StateGroup.STARTED;
        }
        if (to == StateGroup.COMPLETED) {
            return from == null || startsWithChild(from);
        }
        return false;
    }

    /**
     * Si un cambio de estado de una historia es cerrarla o reabrirla (lo que solo puede hacer el project manager):
     * entrar a un estado cerrado (COMPLETED o CANCELLED), salir de uno o moverse entre dos. Devuelve el mensaje del
     * 403, o {@code null} si el cambio es entre dos estados abiertos.
     */
    static String managerOnlyMessage(StateGroup from, StateGroup to) {
        if (from.isOpen() && to.isOpen()) {
            return null;
        }
        return to.isOpen() ? REOPEN_FORBIDDEN : CLOSE_FORBIDDEN;
    }

    /**
     * Mantiene quién y cuándo cerró una historia después de un cambio de estado: se fija al entrar a un estado
     * cerrado y se borra al reabrirla. Moverla entre dos estados cerrados conserva la fecha. En las tareas y los bugs
     * no hace nada.
     *
     * @param previousGroup grupo del estado anterior, o {@code null} si la historia es nueva
     */
    static void updateClosed(Issue issue, StateGroup previousGroup, User actor, Instant now) {
        if (!issue.isStory()) {
            return;
        }
        if (issue.getState().getStateGroup().isOpen()) {
            issue.setClosedAt(null);
            issue.setClosedBy(null);
        } else if (previousGroup == null || previousGroup.isOpen() || issue.getClosedAt() == null) {
            issue.setClosedAt(now);
            issue.setClosedBy(actor);
        }
    }

    /**
     * «Luis empezó WEB-41», o «Luis terminó WEB-41» si la tarea ya está completada (pasó directo de Backlog o Todo a
     * COMPLETED): lo que movió una historia sola.
     */
    static String startedBy(User actor, Issue task) {
        String name = actor == null || actor.getFullName() == null || actor.getFullName().isBlank()
                ? "Alguien"
                : actor.getFullName().trim().split("\\s+")[0];
        boolean completed = task.getState() != null && task.getState().getStateGroup() == StateGroup.COMPLETED;
        return name + (completed ? " terminó " : " empezó ") + task.getCode();
    }
}
