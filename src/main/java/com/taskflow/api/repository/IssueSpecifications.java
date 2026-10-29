package com.taskflow.api.repository;

import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.entity.enums.IssueRelationType;
import com.taskflow.api.entity.enums.StateGroup;
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
            if (hasValues(filter.type())) {
                predicates.add(root.get("type").in(filter.type()));
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
            if (filter.blocked() != null) {
                Predicate blocked = root.get("id").in(openlyBlockedIssues(query, cb));
                predicates.add(filter.blocked() ? blocked : cb.not(blocked));
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                predicates.add(searchPredicate(root, cb, filter.search().trim(), false));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Búsqueda global: tareas de los proyectos indicados cuyo título contiene el texto o cuyo código coincide.
     * A diferencia del filtro del listado, «WEB-12» exige también el identificador del proyecto.
     */
    public static Specification<Issue> searchInProjects(Collection<UUID> projectIds, String search) {
        return (root, query, cb) -> cb.and(
                root.get("project").get("id").in(projectIds),
                searchPredicate(root, cb, search.trim(), true));
    }

    /** Tareas cuyo código es exactamente el indicado (por ejemplo «web-12»), en los proyectos indicados. */
    public static Specification<Issue> codeEquals(Collection<UUID> projectIds, String code) {
        String text = code.trim();
        return (root, query, cb) -> cb.and(
                root.get("project").get("id").in(projectIds),
                cb.equal(cb.lower(root.get("project").get("identifier")), codePrefix(text)),
                cb.equal(root.get("sequenceId"), codeNumber(text)));
    }

    /**
     * Tareas cuyo código es alguno de los indicados (códigos completos, como «WEB-12»; sin importar mayúsculas), en
     * los proyectos indicados.
     */
    public static Specification<Issue> codeIn(Collection<UUID> projectIds, Collection<String> codes) {
        return (root, query, cb) -> cb.and(
                root.get("project").get("id").in(projectIds),
                cb.or(codes.stream()
                        .map(code -> cb.and(
                                cb.equal(cb.lower(root.get("project").get("identifier")), codePrefix(code.trim())),
                                cb.equal(root.get("sequenceId"), codeNumber(code.trim()))))
                        .toArray(Predicate[]::new)));
    }

    /** Si el texto es un código completo con prefijo, como «WEB-12» (sin importar mayúsculas). */
    public static boolean isFullCode(String text) {
        return text != null && text.trim().matches(CODE_PATTERN) && text.trim().contains("-");
    }

    private static Subquery<UUID> issuesRelatedTo(CriteriaQuery<?> query, CriteriaBuilder cb,
                                                  String relation, Collection<UUID> ids) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<Issue> issue = subquery.from(Issue.class);
        subquery.select(issue.get("id"))
                .where(issue.join(relation).get("id").in(ids));
        return subquery;
    }

    /**
     * Ids de las tareas con al menos un bloqueo abierto (la tarea que bloquea no está completada ni cancelada), de
     * cualquier proyecto. Es la misma regla que {@code Issue.blockedByCount}.
     */
    private static Subquery<UUID> openlyBlockedIssues(CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<IssueRelation> relation = subquery.from(IssueRelation.class);
        subquery.select(relation.get("target").get("id"))
                .where(cb.equal(relation.get("type"), IssueRelationType.BLOCKS),
                        cb.not(relation.get("source").get("state").get("stateGroup").in(StateGroup.CLOSED)));
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

    /**
     * Busca en el título; si el texto parece un código («WEB-12» o «12»), también por número. Con
     * {@code matchIdentifier}, un código con prefijo solo coincide en el proyecto de ese identificador.
     */
    private static Predicate searchPredicate(Root<Issue> root, CriteriaBuilder cb, String search,
                                             boolean matchIdentifier) {
        String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
        Predicate byTitle = cb.like(cb.lower(root.get("title")), pattern, LIKE_ESCAPE);
        if (search.matches(CODE_PATTERN)) {
            Predicate byCode = cb.equal(root.get("sequenceId"), codeNumber(search));
            if (matchIdentifier && search.contains("-")) {
                byCode = cb.and(byCode, cb.equal(cb.lower(root.get("project").get("identifier")), codePrefix(search)));
            }
            return cb.or(byTitle, byCode);
        }
        return byTitle;
    }

    private static int codeNumber(String code) {
        return Integer.parseInt(code.substring(code.lastIndexOf('-') + 1));
    }

    /** Identificador del código en minúsculas («WEB-12» → «web»). */
    private static String codePrefix(String code) {
        return code.substring(0, code.lastIndexOf('-')).toLowerCase(Locale.ROOT);
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
