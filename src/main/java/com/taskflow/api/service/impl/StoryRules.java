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

    private StoryRules() {
    }

    /**
     * «Lista para revisar»: la historia está abierta, tiene al menos una tarea, todas sus tareas están completadas o
     * canceladas y ningún bug ligado sigue abierto. La misma regla que {@code Issue.readyForReview}.
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
            if (child.type() == IssueType.TASK) {
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

    /** «Luis empezó WEB-41»: lo que movió una historia sola. */
    static String startedBy(User actor, Issue task) {
        String name = actor == null || actor.getFullName() == null || actor.getFullName().isBlank()
                ? "Alguien"
                : actor.getFullName().trim().split("\\s+")[0];
        return name + " empezó " + task.getCode();
    }
}
