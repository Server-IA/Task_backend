package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.StateGroup;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Estadísticas de un proyecto (o de las tareas asignadas a las personas del filtro {@code assignee}). Las semanas
 * empiezan el lunes, en la zona horaria de la aplicación.
 * Abierta = ni completada ni cancelada.
 *
 * @param openCount             tareas abiertas
 * @param createdThisWeek       tareas creadas esta semana
 * @param completedThisWeek     tareas completadas esta semana
 * @param completedLastWeek     tareas completadas la semana pasada
 * @param overdue               abiertas con fecha límite anterior a hoy (máximo 10, la más vencida primero)
 * @param cycleTimeDays         mediana de días «de En curso a Hecho» (de {@code startedAt} a {@code completedAt}) de
 *                              las tareas completadas en el periodo elegido; las que nunca pasaron por un estado
 *                              STARTED no cuentan; {@code null} si no queda ninguna
 * @param previousCycleTimeDays lo mismo para el periodo anterior de igual duración
 * @param throughput            completadas por semana: exactamente {@code weeks} semanas, de la más antigua
 *                              a la actual
 * @param burndown              ciclo en curso; {@code null} si no hay ninguno
 * @param openByState           abiertas por estado (solo estados abiertos, en el orden del tablero, con ceros)
 * @param workload              abiertas por asignado ({@code user: null} = sin asignar), de más a menos
 * @param stale                 abiertas sin cambios hace más de 14 días (máximo 5, la más antigua primero)
 */
public record ProjectStatsResponse(
        long openCount,
        long createdThisWeek,
        long completedThisWeek,
        long completedLastWeek,
        List<IssueSummaryResponse> overdue,
        Double cycleTimeDays,
        Double previousCycleTimeDays,
        List<ThroughputWeek> throughput,
        Burndown burndown,
        List<OpenStateCount> openByState,
        List<WorkloadEntry> workload,
        List<StaleIssue> stale
) {

    public record ThroughputWeek(LocalDate weekStart, long completed) {
    }

    /**
     * @param total  tareas del ciclo ahora mismo (sin contar las canceladas)
     * @param points un punto por día desde el inicio del ciclo hasta hoy (o su fin)
     */
    public record Burndown(
            UUID cycleId,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            long total,
            List<BurndownPoint> points
    ) {
    }

    /** @param remaining tareas del ciclo sin completar al final de ese día */
    public record BurndownPoint(LocalDate date, long remaining) {
    }

    public record OpenStateCount(UUID stateId, String name, String color, StateGroup group, long count) {
    }

    /** @param user {@code null} para las tareas sin asignar */
    public record WorkloadEntry(UserSummaryResponse user, long open, long overdue) {
    }

    public record StaleIssue(UUID id, String code, String title, long daysWithoutChanges) {
    }
}
