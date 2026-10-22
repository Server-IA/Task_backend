package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
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
            throw new BadRequestException("Todos los issues deben pertenecer al proyecto");
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

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
