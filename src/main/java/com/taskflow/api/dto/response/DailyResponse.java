package com.taskflow.api.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Daily de un proyecto para un día.
 *
 * @param date         el día de la reunión
 * @param previousDate el día de «ayer»: el último día antes de {@code date} con actividad en el proyecto (historial
 *                     de las tareas y comentarios), sin pasar del día hábil anterior (el día antes o, si {@code date}
 *                     es lunes, sábado o domingo, el viernes). Sin actividad en los 14 días anteriores, el día hábil
 *                     anterior
 * @param cycle        ciclo en curso ese día (sin cerrar y con {@code date} entre sus fechas); {@code null} si no hay
 * @param summary      resumen del proyecto desde {@code previousDate} hasta antes de {@code date}
 * @param people       miembros del proyecto y asignados a sus tareas abiertas, por nombre (aunque no tengan nada)
 * @param note         nota de la reunión de ese día; {@code null} si no hay
 */
public record DailyResponse(
        LocalDate date,
        LocalDate previousDate,
        DailyCycle cycle,
        DailySummary summary,
        List<DailyPerson> people,
        DailyNoteResponse note
) {

    /** El ciclo en curso («Sprint 1 · día 13 de 15»). */
    public record DailyCycle(
            UUID id,
            String name,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }

    /**
     * @param moved     tareas distintas que cambiaron de estado
     * @param started   tareas que empezaron (entraron por primera vez a un estado STARTED)
     * @param completed tareas completadas
     * @param comments  comentarios escritos
     * @param blocked   tareas abiertas asignadas que hoy tienen bloqueos abiertos (las de {@code blockers})
     */
    public record DailySummary(
            int moved,
            int started,
            int completed,
            int comments,
            int blocked
    ) {
    }

    /**
     * @param yesterday           lo que la persona hizo desde {@code previousDate} hasta antes de {@code date}: una
     *                            entrada por tarea con el cambio más importante, máximo 8
     * @param today               sus tareas abiertas en curso (grupo STARTED) o con fecha límite hasta {@code date},
     *                            por fecha límite, máximo 8
     * @param blockers            sus tareas abiertas con bloqueos abiertos
     * @param openCount           sus tareas abiertas asignadas en el proyecto
     * @param cycleAssignedCount  sus tareas asignadas en el ciclo en curso, sin las canceladas (0 si no hay ciclo)
     * @param cycleCompletedCount de esas, las completadas
     */
    public record DailyPerson(
            UserSummaryResponse user,
            List<DailyChange> yesterday,
            List<RelatedIssueResponse> today,
            List<DailyBlocker> blockers,
            int openCount,
            int cycleAssignedCount,
            int cycleCompletedCount
    ) {
    }

    /**
     * @param change    «completó», «movió a {estado}», «creó», «comentó» o «actualizó» (cualquier otro cambio)
     * @param fromState en «completó» y «movió a», el nombre del estado de origen tal como quedó en el historial
     *                  ({@code null} en los demás cambios)
     * @param toState   en «completó» y «movió a», el nombre del estado destino ({@code null} en los demás)
     */
    public record DailyChange(
            RelatedIssueResponse issue,
            String change,
            String fromState,
            String toState
    ) {
    }

    /**
     * @param blockedBy tareas abiertas que la bloquean, por código, con su estado y sus asignados (pueden ser de otro
     *                  proyecto)
     */
    public record DailyBlocker(
            RelatedIssueResponse issue,
            List<RelatedIssueResponse> blockedBy
    ) {
    }
}
