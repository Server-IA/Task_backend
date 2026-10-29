package com.taskflow.api.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Orden del listado de tareas. {@code sort=priorityRank,asc} pone primero las urgentes, luego alta, media, baja y
 * sin prioridad; dentro de la misma prioridad van por fecha límite (las que no tienen al final). Cualquier orden
 * termina por número de tarea ({@code sequenceId}, único en el proyecto), así el orden es estable entre páginas: sin él,
 * las tareas empatadas (el mismo {@code sortOrder} en dos columnas, la misma fecha…) podían repetirse o faltar al
 * pasar de página.
 */
public final class IssueListSort {

    /** Propiedad de {@code Issue} con la posición de la prioridad (0 urgente … 4 sin prioridad). */
    public static final String PRIORITY = "priorityRank";

    private static final String NUMBER = "sequenceId";

    private IssueListSort() {
    }

    /**
     * Agrega «luego número» al final (salvo que ya venga en el orden) y, si se ordena por prioridad, antes «luego fecha
     * límite».
     */
    public static Pageable withTieBreakers(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return pageable;
        }
        Sort sort = pageable.getSort();
        Sort result = sort;
        if (sort.getOrderFor(PRIORITY) != null && sort.getOrderFor("dueDate") == null) {
            result = result.and(Sort.by(Sort.Direction.ASC, "dueDate"));
        }
        if (sort.getOrderFor(NUMBER) == null) {
            result = result.and(Sort.by(Sort.Direction.ASC, NUMBER));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), result);
    }
}
