package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.response.DevelopmentStatsResponse;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.MergedWeek;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.OldPullRequest;
import com.taskflow.api.dto.response.DevelopmentStatsResponse.WorkflowStats;
import com.taskflow.api.entity.Project;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.github.GithubStore;
import com.taskflow.api.github.GithubStore.MergedPull;
import com.taskflow.api.github.GithubStore.WorkflowSummary;
import com.taskflow.api.service.DevelopmentStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Las estadísticas de desarrollo de un proyecto: lee de las tablas de GitHub (solo los repositorios enlazados al
 * proyecto) y arma las cifras, la tendencia semanal y la tabla por workflow. Las semanas empiezan el lunes, en la
 * zona horaria de la aplicación, como en las demás estadísticas.
 */
@Service
@RequiredArgsConstructor
public class DevelopmentStatsServiceImpl implements DevelopmentStatsService {

    private final GithubStore store;
    private final ResourceLookup lookup;
    private final AppProperties properties;

    @Override
    @Transactional(readOnly = true)
    public DevelopmentStatsResponse getStats(String slug, UUID projectId, int weeks) {
        if (!ProjectStatsServiceImpl.ALLOWED_WEEKS.contains(weeks)) {
            throw new BadRequestException("El parámetro weeks debe ser 4, 8 o 12");
        }
        return calculate(lookup.project(slug, projectId), weeks, Instant.now());
    }

    /** Las del proyecto ya resuelto (lo usa también el reporte); {@code weeks} ya validado. */
    DevelopmentStatsResponse calculate(Project project, int weeks, Instant now) {
        ZoneId zone = ZoneId.of(properties.timezone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate currentWeek = ProjectStatsCalculator.startOfWeek(today);
        LocalDate periodStart = currentWeek.minusWeeks(weeks - 1L);
        Instant since = periodStart.atStartOfDay(zone).toInstant();
        UUID projectId = project.getId();

        int repositories = store.repositoryCount(projectId);
        if (repositories == 0) {
            return DevelopmentStatsResponse.empty(weeks, periodStart, mergedByWeek(List.of(), periodStart, weeks, zone));
        }

        long[] open = store.openPullCounts(projectId);
        List<MergedPull> merged = store.mergedPullsSince(projectId, since);
        List<WorkflowSummary> workflows = store.workflowsSince(projectId, since);
        long[] completed = store.completedIssuesWithMergedPull(projectId, since);
        Instant oldBefore = now.minus(Duration.ofDays(DevelopmentStatsResponse.OLD_PULL_REQUEST_DAYS));

        return new DevelopmentStatsResponse(
                repositories,
                weeks,
                periodStart,
                open[0],
                open[1],
                merged.size(),
                averageHoursToMerge(merged),
                workflows.stream().mapToLong(WorkflowSummary::runs).sum(),
                workflows.stream().mapToLong(WorkflowSummary::succeeded).sum(),
                completed[0],
                completed[1],
                mergedByWeek(merged, periodStart, weeks, zone),
                workflows.stream()
                        .map(workflow -> new WorkflowStats(workflow.repository(), workflow.name(), workflow.runs(),
                                workflow.succeeded(),
                                workflow.averageSeconds() == null ? null : Math.round(workflow.averageSeconds()),
                                workflow.lastRunAt(), workflow.lastConclusion()))
                        .toList(),
                store.openPullsOpenedBefore(projectId, oldBefore, DevelopmentStatsResponse.MAX_OLD_PULL_REQUESTS).stream()
                        .map(pull -> new OldPullRequest(pull.id(), pull.repository(), pull.number(), pull.title(), pull.url(),
                                "DRAFT".equals(pull.state()),
                                ChronoUnit.DAYS.between(LocalDate.ofInstant(pull.openedAt(), zone), today),
                                pull.issueId(), pull.issueCode(), pull.issueTitle()))
                        .toList());
    }

    /**
     * Horas promedio de apertura a fusión, con un decimal. Los que no traen cuándo se abrieron (o vienen al revés) no
     * cuentan; {@code null} si no queda ninguno.
     */
    static Double averageHoursToMerge(List<MergedPull> merged) {
        double[] hours = merged.stream()
                .filter(pull -> pull.openedAt() != null && !pull.mergedAt().isBefore(pull.openedAt()))
                .mapToDouble(pull -> Duration.between(pull.openedAt(), pull.mergedAt()).toMillis() / 3_600_000.0)
                .toArray();
        if (hours.length == 0) {
            return null;
        }
        double average = java.util.Arrays.stream(hours).average().orElseThrow();
        return Math.round(average * 10) / 10.0;
    }

    /** Fusionados por semana: exactamente {@code weeks} semanas desde {@code periodStart}, con ceros. */
    static List<MergedWeek> mergedByWeek(List<MergedPull> merged, LocalDate periodStart, int weeks, ZoneId zone) {
        long[] counts = new long[weeks];
        for (MergedPull pull : merged) {
            long week = ChronoUnit.WEEKS.between(periodStart, LocalDate.ofInstant(pull.mergedAt(), zone));
            if (week >= 0 && week < weeks) {
                counts[(int) week]++;
            }
        }
        List<MergedWeek> result = new ArrayList<>(weeks);
        for (int i = 0; i < weeks; i++) {
            result.add(new MergedWeek(periodStart.plusWeeks(i), counts[i]));
        }
        return result;
    }
}
