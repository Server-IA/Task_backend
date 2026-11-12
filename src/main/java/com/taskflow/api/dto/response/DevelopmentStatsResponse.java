package com.taskflow.api.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Estadísticas de desarrollo de un proyecto: sus pull requests y sus ejecuciones de GitHub Actions, de los
 * repositorios enlazados al proyecto. El periodo son las últimas {@code weeks} semanas (desde el lunes de la primera,
 * en la zona horaria de la aplicación), igual que el ritmo de cierre de las estadísticas.
 * <p>
 * Sin repositorios enlazados ({@code repositories = 0}) todo lo demás va en cero o vacío.
 *
 * @param repositories                repositorios de GitHub enlazados al proyecto
 * @param weeks                       semanas del periodo
 * @param periodStart                 primer día del periodo (un lunes)
 * @param openPullRequests            pull requests abiertos ahora (incluidos los borradores)
 * @param draftPullRequests           de esos, cuántos son borradores
 * @param mergedPullRequests          pull requests fusionados en el periodo
 * @param averageHoursToMerge         horas promedio de apertura a fusión de los fusionados en el periodo, con un
 *                                    decimal; {@code null} si no hay ninguno
 * @param runsFinished                ejecuciones de Actions terminadas en el periodo, sin contar las canceladas ni las
 *                                    omitidas
 * @param runsSucceeded               de esas, las correctas
 * @param completedIssues             tareas completadas en el periodo (sin las historias, como las estadísticas)
 * @param completedWithMergedPullRequest de esas, las que tienen un pull request fusionado
 * @param mergedByWeek                fusionados por semana: exactamente {@code weeks} semanas, de la más antigua a la
 *                                    actual
 * @param workflows                   cada workflow con ejecuciones en el periodo, del que más tiene al que menos
 * @param oldOpenPullRequests         abiertos hace más de {@value #OLD_PULL_REQUEST_DAYS} días (como mucho
 *                                    {@value #MAX_OLD_PULL_REQUESTS}, el más antiguo primero)
 */
public record DevelopmentStatsResponse(
        int repositories,
        int weeks,
        LocalDate periodStart,
        long openPullRequests,
        long draftPullRequests,
        long mergedPullRequests,
        Double averageHoursToMerge,
        long runsFinished,
        long runsSucceeded,
        long completedIssues,
        long completedWithMergedPullRequest,
        List<MergedWeek> mergedByWeek,
        List<WorkflowStats> workflows,
        List<OldPullRequest> oldOpenPullRequests
) {

    /** Un pull request abierto hace más de estos días se considera viejo. */
    public static final int OLD_PULL_REQUEST_DAYS = 7;
    /** Pull requests viejos que se listan como mucho. */
    public static final int MAX_OLD_PULL_REQUESTS = 5;

    public record MergedWeek(LocalDate weekStart, long merged) {
    }

    /**
     * Un workflow en el periodo.
     *
     * @param repository     repositorio («acme/sitio-web»)
     * @param name           nombre del workflow
     * @param runs           ejecuciones terminadas en el periodo, sin las canceladas ni las omitidas
     * @param succeeded      de esas, las correctas
     * @param averageSeconds duración media en segundos; {@code null} si ninguna trae su hora de inicio
     * @param lastRunAt      cuándo empezó la última
     * @param lastConclusion cómo terminó la última (success, failure, timed_out…)
     */
    public record WorkflowStats(
            String repository,
            String name,
            long runs,
            long succeeded,
            Long averageSeconds,
            Instant lastRunAt,
            String lastConclusion
    ) {
    }

    /**
     * Un pull request abierto hace tiempo, con su tarea si es de este proyecto.
     *
     * @param daysOpen días desde que se abrió
     */
    public record OldPullRequest(
            UUID id,
            String repository,
            int number,
            String title,
            String url,
            boolean draft,
            long daysOpen,
            UUID issueId,
            String issueCode,
            String issueTitle
    ) {
    }

    /** Un proyecto sin repositorios de GitHub enlazados. */
    public static DevelopmentStatsResponse empty(int weeks, LocalDate periodStart, List<MergedWeek> mergedByWeek) {
        return new DevelopmentStatsResponse(0, weeks, periodStart, 0, 0, 0, null, 0, 0, 0, 0, mergedByWeek, List.of(),
                List.of());
    }
}
