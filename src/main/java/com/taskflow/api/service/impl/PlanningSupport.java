package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.CycleStatus;
import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.repository.IssueProgress;
import com.taskflow.api.repository.IssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lógica compartida por ciclos y módulos.
 */
@Component
@RequiredArgsConstructor
class PlanningSupport {

    private final IssueRepository issueRepository;

    /** Issues del proyecto con esos ids; falla si alguno no existe o es de otro proyecto. */
    List<Issue> findIssues(Project project, Collection<UUID> ids) {
        Set<UUID> distinct = new HashSet<>(ids);
        List<Issue> issues = issueRepository.findAllByProjectIdAndIdIn(project.getId(), distinct);
        if (issues.size() != distinct.size()) {
            throw new BadRequestException("Todas las tareas deben pertenecer al proyecto");
        }
        return issues;
    }

    static Map<UUID, ProgressResponse> progressById(List<IssueProgress> rows) {
        return rows.stream().collect(Collectors.toMap(IssueProgress::getId,
                row -> ProgressResponse.of(row.getTotal(), row.getCompleted()), (a, b) -> a));
    }

    static ProgressResponse progressOf(UUID id, Function<List<UUID>, List<IssueProgress>> query) {
        return progressById(query.apply(List.of(id))).getOrDefault(id, ProgressResponse.of(0, 0));
    }

    static void validateDates(LocalDate start, LocalDate end, String message) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new BadRequestException(message);
        }
    }

    /**
     * Ciclo en curso el día indicado (sin cerrar y con el día entre sus fechas); si se solapan varios, el primero de
     * la lista ({@code CycleRepository.findAllByProjectId} los ordena por inicio, del más reciente al más antiguo).
     * {@code null} si no hay.
     */
    static Cycle currentCycle(List<Cycle> cycles, LocalDate day) {
        return cycles.stream()
                .filter(cycle -> CycleStatus.of(cycle.getStartDate(), cycle.getEndDate(), cycle.isClosed(), day)
                        == CycleStatus.CURRENT)
                .findFirst()
                .orElse(null);
    }

    /** Trabajo que cuenta en el avance y los resúmenes: tareas y bugs (las historias avanzan con sus tareas). */
    static boolean isWork(Issue issue) {
        return issue.getType() != IssueType.STORY;
    }

    /** En el grupo COMPLETED. */
    static boolean isCompleted(Issue issue) {
        return issue.getState().getStateGroup() == StateGroup.COMPLETED;
    }

    /** Sin terminar: ni completada ni cancelada. */
    static boolean isOpen(Issue issue) {
        return issue.getState().getStateGroup().isOpen();
    }

    /**
     * Mediana de días «de En curso a Hecho» (de {@code startedAt} a {@code completedAt}) de las tareas completadas,
     * redondeada a un decimal (igual que en las estadísticas del proyecto). Las que nunca pasaron por un estado
     * STARTED no cuentan; {@code null} si no queda ninguna.
     */
    static Double cycleTimeDays(Collection<Issue> issues) {
        return ProjectStatsCalculator.roundedMedianDays(issues.stream()
                .filter(PlanningSupport::isCompleted)
                .map(issue -> ProjectStatsCalculator.cycleTime(issue.getStartedAt(), issue.getCompletedAt()))
                .toList());
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
