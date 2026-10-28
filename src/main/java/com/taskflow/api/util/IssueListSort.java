package com.taskflow.api.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Orden del listado de tareas. {@code sort=priorityRank,asc} pone primero las urgentes, luego alta, media, baja y
 * sin prioridad; dentro de la misma prioridad van por fecha límite (las que no tienen al final) y luego por número,
 * así el orden es estable entre páginas.
 */
public final class IssueListSort {

    /** Propiedad de {@code Issue} con la posición de la prioridad (0 urgente … 4 sin prioridad). */
    public static final String PRIORITY = "priorityRank";

    private IssueListSort() {
    }

    /**
     * Si se ordena por prioridad, agrega «luego fecha límite» y «luego número» (salvo que ya vengan en el orden).
     * Cualquier otro orden se devuelve igual.
     */
    public static Pageable withTieBreakers(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (pageable.isUnpaged() || sort.getOrderFor(PRIORITY) == null) {
            return pageable;
        }
        Sort result = sort;
        if (sort.getOrderFor("dueDate") == null) {
            result = result.and(Sort.by(Sort.Direction.ASC, "dueDate"));
        }
        if (sort.getOrderFor("sequenceId") == null) {
            result = result.and(Sort.by(Sort.Direction.ASC, "sequenceId"));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), result);
    }
}
