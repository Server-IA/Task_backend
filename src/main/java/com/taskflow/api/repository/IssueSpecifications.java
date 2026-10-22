package com.taskflow.api.repository;

import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.ProjectModule;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Construye la consulta del listado de issues a partir de los filtros.
 */
public final class IssueSpecifications {

    private static final String CODE_PATTERN = "^([A-Za-z][A-Za-z0-9]*-)?[0-9]{1,9}$";
    private static final char LIKE_ESCAPE = '!';

    private IssueSpecifications() {
    }

    public static Specification<Issue> matching(UUID projectId, IssueFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("id"), projectId));

            if (hasValues(filter.state())) {
                predicates.add(root.get("state").get("id").in(filter.state()));
            }
            if (hasValues(filter.priority())) {
                predicates.add(root.get("priority").in(filter.priority()));
            }
            // Asignados y etiquetas van en subconsultas para no duplicar filas (y no romper la paginación).
            if (hasValues(filter.assignee())) {
                predicates.add(root.get("id").in(issuesRelatedTo(query, cb, "assignees", filter.assignee())));
            }
            if (hasValues(filter.label())) {
                predicates.add(root.get("id").in(issuesRelatedTo(query, cb, "labels", filter.label())));
            }
            if (filter.cycle() != null) {
                predicates.add(root.get("id").in(issuesOf(query, Cycle.class, filter.cycle())));
            }
            if (filter.module() != null) {
                predicates.add(root.get("id").in(issuesOf(query, ProjectModule.class, filter.module())));
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                predicates.add(searchPredicate(root, cb, filter.search().trim()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Subquery<UUID> issuesRelatedTo(CriteriaQuery<?> query, CriteriaBuilder cb,
                                                  String relation, Collection<UUID> ids) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<Issue> issue = subquery.from(Issue.class);
        subquery.select(issue.get("id"))
                .where(issue.join(relation).get("id").in(ids));
        return subquery;
    }

    /** Ids de los issues de un ciclo o módulo. */
    private static <T> Subquery<UUID> issuesOf(CriteriaQuery<?> query, Class<T> container, UUID containerId) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<T> root = subquery.from(container);
        subquery.select(root.join("issues").get("id"))
                .where(root.get("id").in(containerId));
        return subquery;
    }

    /** Busca en el título; si el texto parece un código («WEB-12» o «12»), también por número. */
    private static Predicate searchPredicate(Root<Issue> root, CriteriaBuilder cb, String search) {
        String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
        Predicate byTitle = cb.like(cb.lower(root.get("title")), pattern, LIKE_ESCAPE);
        if (search.matches(CODE_PATTERN)) {
            int sequenceId = Integer.parseInt(search.substring(search.lastIndexOf('-') + 1));
            return cb.or(byTitle, cb.equal(root.get("sequenceId"), sequenceId));
        }
        return byTitle;
    }

    private static String escapeLike(String value) {
        return value
                .replace(String.valueOf(LIKE_ESCAPE), "" + LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }

    private static boolean hasValues(Collection<?> values) {
        return values != null && !values.isEmpty();
    }
}
