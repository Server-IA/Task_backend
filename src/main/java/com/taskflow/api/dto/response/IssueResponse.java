package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IssueResponse(
        UUID id,
        String code,
        int sequenceId,
        UUID projectId,
        /** Proyecto de la tarea con su identificador y su color (para la pastilla en búsquedas y «Mi trabajo»). */
        ProjectRefResponse project,
        /** Historia, tarea o bug. */
        IssueType type,
        String title,
        String description,
        /**
         * Sube con cada cambio de la descripción (no con los de otros campos). Se manda como
         * {@code baseDescriptionVersion} al guardar la descripción para no pisar un cambio ajeno.
         */
        int descriptionVersion,
        /** Último cambio de la descripción y quién lo hizo; {@code null} si nunca tuvo. */
        Instant descriptionUpdatedAt,
        UserSummaryResponse descriptionUpdatedBy,
        StateResponse state,
        IssuePriority priority,
        IssueSummaryResponse parent,
        LocalDate startDate,
        LocalDate dueDate,
        double sortOrder,
        List<UserSummaryResponse> assignees,
        List<LabelResponse> labels,
        PlanningRefResponse cycle,
        List<PlanningRefResponse> modules,
        int subIssueCount,
        int completedSubIssueCount,
        /** Tareas que la bloquean y siguen abiertas; mayor que 0 = «bloqueada». */
        int blockedByCount,
        /**
         * Códigos de esas tareas, ordenados (por ejemplo {@code ["AMC-4", "WEB-2"]}); pueden ser de otros proyectos
         * del workspace. Vacío si no está bloqueada.
         */
        List<String> blockedByCodes,
        /**
         * Códigos de las tareas abiertas que esta bloquea, ordenados; vacío si no bloquea ninguna o si ya está
         * cerrada (una tarea completada o cancelada ya no bloquea).
         */
        List<String> blockingCodes,
        /** Pasos de la checklist (total y hechos). */
        int checklistTotal,
        int checklistDone,
        /** Criterios de aceptación (total y cumplidos). */
        int criteriaTotal,
        int criteriaDone,
        /** Último cambio de los criterios o la checklist; {@code null} si nunca tuvo. */
        Instant checklistUpdatedAt,
        UserSummaryResponse createdBy,
        Instant createdAt,
        Instant updatedAt,
        /**
         * La primera vez que entró a un estado del grupo STARTED; {@code null} si nunca pasó por uno. No se borra al
         * salir del grupo.
         */
        Instant startedAt,
        /** Cuándo se completó (entró a un estado del grupo COMPLETED); {@code null} si no está completada. */
        Instant completedAt,
        /**
         * Historias: tareas ({@code TASK}) hijas sin contar las canceladas, completadas y en curso («3 de 5 tareas» y
         * la barra). Los bugs no cuentan. 0 en las tareas y los bugs.
         */
        int storyTaskCount,
        int storyTaskDoneCount,
        int storyTaskStartedCount,
        /** Historias: bugs hijos abiertos («1 bug abierto»). */
        int openBugCount,
        /** Historias cerradas: bugs hijos creados después de cerrarla («1 bug después de cerrar»). */
        int bugsAfterCloseCount,
        /**
         * Historias: «lista para revisar» (abierta, con al menos una tarea, todas completadas o canceladas y ningún bug
         * ligado abierto). Es una marca: el estado no cambia; la cierra el project manager.
         */
        boolean readyForReview,
        /** Historias: cuándo y quién la cerró («Cerrada por Ana · 12 nov»); {@code null} mientras esté abierta. */
        Instant closedAt,
        UserSummaryResponse closedBy,
        /** Adjuntos de la tarea (el clip de las tarjetas y las filas); las imágenes de los textos no cuentan. */
        int attachmentCount
) {
}
