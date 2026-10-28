package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.IssueSummaryResponse;
import com.taskflow.api.dto.response.ProjectStatsResponse;
import com.taskflow.api.dto.response.ProjectStatsResponse.Burndown;
import com.taskflow.api.dto.response.ProjectStatsResponse.BurndownPoint;
import com.taskflow.api.dto.response.ProjectStatsResponse.OpenStateCount;
import com.taskflow.api.dto.response.ProjectStatsResponse.StaleIssue;
import com.taskflow.api.dto.response.ProjectStatsResponse.ThroughputWeek;
import com.taskflow.api.dto.response.ProjectStatsResponse.WorkloadEntry;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.repository.IssueStatsRow;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Calcula las estadísticas de un proyecto a partir de sus tareas ya leídas, sin tocar la base de datos.
 * Las fechas se interpretan en la zona horaria indicada y las semanas empiezan el lunes.
 */
final class ProjectStatsCalculator {

    static final int MAX_OVERDUE = 10;
    static final int MAX_STALE = 5;
    /** Una tarea abierta sin cambios durante más de estos días se considera estancada. */
    static final int STALE_AFTER_DAYS = 14;

    private static final double MILLIS_PER_DAY = Duration.ofDays(1).toMillis();

    private final ZoneId zone;
    private final LocalDate today;
    private final LocalDate weekStart;

    ProjectStatsCalculator(Instant now, ZoneId zone) {
        this.zone = zone;
        this.today = LocalDate.ofInstant(now, zone);
        this.weekStart = startOfWeek(today);
    }

    /**
     * @param assigneesByIssue asignados de las tareas abiertas, por id de tarea
     * @param currentCycle     ciclo en curso, o {@code null}
     * @param cycleIssueIds    tareas del ciclo en curso
     */
    ProjectStatsResponse calculate(String identifier, int weeks, List<IssueStatsRow> issues, List<State> states,
                                   Map<UUID, List<UserSummaryResponse>> assigneesByIssue,
                                   Cycle currentCycle, Set<UUID> cycleIssueIds) {
        LocalDate periodStart = weekStart.minusWeeks(weeks - 1L);
        LocalDate previousPeriodStart = periodStart.minusWeeks(weeks);
        List<IssueStatsRow> cycleIssues = issues.stream()
                .filter(issue -> cycleIssueIds.contains(issue.id()))
                .toList();

        return new ProjectStatsResponse(
                issues.stream().filter(ProjectStatsCalculator::isOpen).count(),
                issues.stream().filter(issue -> !dateOf(issue.createdAt()).isBefore(weekStart)).count(),
                countCompletedBetween(issues, weekStart, weekStart.plusWeeks(1)),
                countCompletedBetween(issues, weekStart.minusWeeks(1), weekStart),
                overdue(issues, identifier),
                cycleTimeDays(issues, periodStart, weekStart.plusWeeks(1)),
                cycleTimeDays(issues, previousPeriodStart, periodStart),
                throughput(issues, weeks),
                currentCycle == null ? null : burndown(currentCycle, cycleIssues),
                openByState(issues, states),
                workload(issues, assigneesByIssue),
                stale(issues, identifier));
    }

    /** Abiertas con fecha límite anterior a hoy: la más vencida primero, como máximo {@link #MAX_OVERDUE}. */
    List<IssueSummaryResponse> overdue(List<IssueStatsRow> issues, String identifier) {
        return issues.stream()
                .filter(this::isOverdue)
                .sorted(Comparator.comparing(IssueStatsRow::dueDate).thenComparingInt(IssueStatsRow::sequenceId))
                .limit(MAX_OVERDUE)
                .map(issue -> new IssueSummaryResponse(issue.id(), code(identifier, issue), issue.title()))
                .toList();
    }

    /**
     * Mediana de días «de En curso a Hecho» (de {@code startedAt} a {@code completedAt}) de las tareas completadas en
     * {@code [from, to)}, redondeada a un decimal. Las que nunca pasaron por un estado STARTED no cuentan;
     * {@code null} si no queda ninguna.
     */
    Double cycleTimeDays(List<IssueStatsRow> issues, LocalDate from, LocalDate to) {
        return roundedMedianDays(issues.stream()
                .filter(issue -> completedBetween(issue, from, to))
                .map(issue -> cycleTime(issue.startedAt(), issue.completedAt()))
                .toList());
    }

    /**
     * Días entre que la tarea empezó y se completó; {@code null} si no tiene alguna de las dos fechas (nunca pasó por
     * un estado STARTED o no está completada) o si están al revés.
     */
    static Double cycleTime(Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            return null;
        }
        return Duration.between(startedAt, completedAt).toMillis() / MILLIS_PER_DAY;
    }

    /** Mediana de los valores no nulos, redondeada a un decimal; {@code null} si no hay ninguno. */
    static Double roundedMedianDays(Collection<Double> days) {
        Double median = median(days.stream().filter(Objects::nonNull).toList());
        return median == null ? null : Math.round(median * 10) / 10.0;
    }

    /** Completadas por semana: exactamente {@code weeks} semanas, de la más antigua a la actual. */
    List<ThroughputWeek> throughput(List<IssueStatsRow> issues, int weeks) {
        List<ThroughputWeek> result = new ArrayList<>(weeks);
        for (int i = weeks - 1; i >= 0; i--) {
            LocalDate start = weekStart.minusWeeks(i);
            result.add(new ThroughputWeek(start, countCompletedBetween(issues, start, start.plusWeeks(1))));
        }
        return result;
    }

    /**
     * Un punto por día desde el inicio del ciclo hasta hoy (o su fin): las tareas del ciclo (sin las
     * canceladas) que al final de ese día seguían sin completar.
     */
    Burndown burndown(Cycle cycle, List<IssueStatsRow> cycleIssues) {
        List<IssueStatsRow> counted = cycleIssues.stream()
                .filter(issue -> issue.group() != StateGroup.CANCELLED)
                .toList();
        LocalDate last = cycle.getEndDate().isBefore(today) ? cycle.getEndDate() : today;
        List<BurndownPoint> points = new ArrayList<>();
        for (LocalDate day = cycle.getStartDate(); !day.isAfter(last); day = day.plusDays(1)) {
            LocalDate endOfDay = day;
            long remaining = counted.stream()
                    .filter(issue -> issue.completedAt() == null || dateOf(issue.completedAt()).isAfter(endOfDay))
                    .count();
            points.add(new BurndownPoint(day, remaining));
        }
        return new Burndown(cycle.getId(), cycle.getName(), cycle.getStartDate(), cycle.getEndDate(),
                counted.size(), points);
    }

    /** Tareas abiertas por estado: solo estados abiertos, en el orden del tablero, incluidos los vacíos. */
    List<OpenStateCount> openByState(List<IssueStatsRow> issues, List<State> states) {
        Map<UUID, Long> countByState = issues.stream()
                .filter(ProjectStatsCalculator::isOpen)
                .collect(Collectors.groupingBy(IssueStatsRow::stateId, Collectors.counting()));
        return states.stream()
                .filter(state -> state.getStateGroup().isOpen())
                .sorted(Comparator.comparingInt(State::getPosition))
                .map(state -> new OpenStateCount(state.getId(), state.getName(), state.getColor(),
                        state.getStateGroup(), countByState.getOrDefault(state.getId(), 0L)))
                .toList();
    }

    /**
     * Tareas abiertas y vencidas por asignado (una tarea con dos asignados cuenta para ambos);
     * {@code user: null} agrupa las que no tienen asignado. De más a menos tareas abiertas.
     */
    List<WorkloadEntry> workload(List<IssueStatsRow> issues, Map<UUID, List<UserSummaryResponse>> assigneesByIssue) {
        Map<UUID, UserSummaryResponse> users = new LinkedHashMap<>();
        Map<UUID, long[]> counters = new LinkedHashMap<>();
        long[] unassigned = new long[2];
        for (IssueStatsRow issue : issues) {
            if (!isOpen(issue)) {
                continue;
            }
            int overdue = isOverdue(issue) ? 1 : 0;
            List<UserSummaryResponse> assignees = assigneesByIssue.getOrDefault(issue.id(), List.of());
            if (assignees.isEmpty()) {
                unassigned[0]++;
                unassigned[1] += overdue;
            }
            for (UserSummaryResponse user : assignees) {
                users.putIfAbsent(user.id(), user);
                long[] counter = counters.computeIfAbsent(user.id(), id -> new long[2]);
                counter[0]++;
                counter[1] += overdue;
            }
        }
        List<WorkloadEntry> result = new ArrayList<>();
        counters.forEach((userId, counter) -> result.add(new WorkloadEntry(users.get(userId), counter[0], counter[1])));
        if (unassigned[0] > 0) {
            result.add(new WorkloadEntry(null, unassigned[0], unassigned[1]));
        }
        result.sort(Comparator.comparingLong(WorkloadEntry::open).reversed()
                .thenComparing(Comparator.comparingLong(WorkloadEntry::overdue).reversed())
                .thenComparing(entry -> entry.user() == null)
                .thenComparing(entry -> entry.user() == null ? "" : entry.user().fullName(),
                        String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Abiertas sin cambios hace más de {@link #STALE_AFTER_DAYS} días: la más antigua primero, máximo 5. */
    List<StaleIssue> stale(List<IssueStatsRow> issues, String identifier) {
        return issues.stream()
                .filter(ProjectStatsCalculator::isOpen)
                .filter(issue -> daysSince(issue.updatedAt()) > STALE_AFTER_DAYS)
                .sorted(Comparator.comparing(IssueStatsRow::updatedAt).thenComparingInt(IssueStatsRow::sequenceId))
                .limit(MAX_STALE)
                .map(issue -> new StaleIssue(issue.id(), code(identifier, issue), issue.title(),
                        daysSince(issue.updatedAt())))
                .toList();
    }

    /** Mediana (promedio de los dos centrales si la cantidad es par); {@code null} si no hay valores. */
    static Double median(Collection<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    static LocalDate startOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private long countCompletedBetween(List<IssueStatsRow> issues, LocalDate from, LocalDate to) {
        return issues.stream().filter(issue -> completedBetween(issue, from, to)).count();
    }

    /** Completada en {@code [from, to)} (fechas en la zona horaria de la aplicación). */
    private boolean completedBetween(IssueStatsRow issue, LocalDate from, LocalDate to) {
        if (issue.completedAt() == null) {
            return false;
        }
        LocalDate date = dateOf(issue.completedAt());
        return !date.isBefore(from) && date.isBefore(to);
    }

    private boolean isOverdue(IssueStatsRow issue) {
        return isOpen(issue) && issue.dueDate() != null && issue.dueDate().isBefore(today);
    }

    private long daysSince(Instant instant) {
        return ChronoUnit.DAYS.between(dateOf(instant), today);
    }

    private LocalDate dateOf(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }

    private static boolean isOpen(IssueStatsRow issue) {
        return issue.group().isOpen();
    }

    private static String code(String identifier, IssueStatsRow issue) {
        return identifier + "-" + issue.sequenceId();
    }

    /** Agrupa filas por id, conservando el orden. */
    static <T, K, V> Map<K, List<V>> groupBy(Collection<T> rows, Function<T, K> key, Function<T, V> value) {
        return rows.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new,
                Collectors.mapping(value, Collectors.toList())));
    }
}
