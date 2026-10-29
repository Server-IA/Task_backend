package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssueType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Referencia a una tarea con lo necesario para mostrarla: la otra tarea de una dependencia (que puede ser de otro
 * proyecto del workspace), las tareas del cierre de un ciclo, la tarea creada desde una acción de la retrospectiva o
 * las de la daily y «Mi trabajo».
 *
 * @param title                  {@code null} solo en una dependencia con una tarea de un proyecto al que el usuario no
 *                               tiene acceso (ver {@code IssueRelationResponse.accessible})
 * @param assignees              vacío en ese mismo caso
 * @param project                proyecto de la tarea (su {@code identifier} es el prefijo del código)
 * @param blockedByCount         tareas abiertas que la bloquean; mayor que 0 = «bloqueada»
 * @param subIssueCount          sub-tareas (total)
 * @param completedSubIssueCount sub-tareas completadas
 * @param checklistTotal         pasos de la checklist
 * @param checklistDone          pasos hechos
 * @param type                   historia, tarea o bug (para su icono)
 */
public record RelatedIssueResponse(
        UUID id,
        String code,
        String title,
        StateResponse state,
        List<UserSummaryResponse> assignees,
        ProjectRefResponse project,
        LocalDate dueDate,
        int blockedByCount,
        int subIssueCount,
        int completedSubIssueCount,
        int checklistTotal,
        int checklistDone,
        IssueType type
) {

    /** Una tarea ({@code TASK}). */
    public RelatedIssueResponse(UUID id, String code, String title, StateResponse state,
                                List<UserSummaryResponse> assignees, ProjectRefResponse project, LocalDate dueDate,
                                int blockedByCount, int subIssueCount, int completedSubIssueCount, int checklistTotal,
                                int checklistDone) {
        this(id, code, title, state, assignees, project, dueDate, blockedByCount, subIssueCount,
                completedSubIssueCount, checklistTotal, checklistDone, IssueType.TASK);
    }
}
