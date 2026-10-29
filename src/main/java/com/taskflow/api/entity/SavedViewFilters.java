package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Filtros de una vista guardada (se guardan como jsonb). Usan los mismos nombres que los parámetros del listado de
 * tareas; {@code view} indica si se muestra como lista o tablero, {@code sort} el orden (por ejemplo
 * {@code "priorityRank,asc"} o {@code "dueDate,asc"}) y {@code groupBy} cómo se agrupa la lista.
 * {@code type}: los tipos que se ven; {@code null} o vacío = los de siempre del tablero y la lista (tareas y bugs,
 * sin historias).
 */
public record SavedViewFilters(
        @Size(max = 50, message = "Máximo 50 estados")
        List<@NotNull(message = "Los estados no pueden ser nulos") UUID> state,

        @Size(max = 5, message = "Máximo 5 prioridades")
        List<@NotNull(message = "Las prioridades no pueden ser nulas") IssuePriority> priority,

        @Size(max = 50, message = "Máximo 50 asignados")
        List<@NotNull(message = "Los asignados no pueden ser nulos") UUID> assignee,

        @Size(max = 50, message = "Máximo 50 etiquetas")
        List<@NotNull(message = "Las etiquetas no pueden ser nulas") UUID> label,

        @Size(max = 200, message = "La búsqueda no puede superar 200 caracteres")
        String search,

        @Pattern(regexp = "list|board", message = "view debe ser list o board")
        String view,

        @Size(max = 60, message = "El orden no puede superar 60 caracteres")
        @Pattern(regexp = SORT_PATTERN, message = "El orden debe tener el formato campo o campo,asc|desc")
        String sort,

        /** Id del ciclo. */
        UUID cycle,

        /** Id del módulo. */
        UUID module,

        /** {@code true}: solo las bloqueadas; {@code false}: solo las que no lo están; {@code null}: todas. */
        Boolean blocked,

        @Pattern(regexp = GROUP_BY_PATTERN, message = "groupBy debe ser state, priority, assignee, label, type o story")
        String groupBy,

        @Size(max = 3, message = "Máximo 3 tipos")
        List<@NotNull(message = "Los tipos no pueden ser nulos") IssueType> type
) {

    /** Nombre de un campo y, opcionalmente, la dirección: {@code dueDate} o {@code dueDate,desc}. */
    public static final String SORT_PATTERN = "^[A-Za-z][A-Za-z0-9_.]*(,(asc|desc|ASC|DESC))?$";

    /** Agrupación de la lista; {@code null} = sin agrupar. */
    public static final String GROUP_BY_PATTERN = "state|priority|assignee|label|type|story";

    /** Tipos que muestran el tablero y la lista cuando la vista no elige: las tareas y los bugs (no las historias). */
    public static final List<IssueType> DEFAULT_TYPES = List.of(IssueType.TASK, IssueType.BUG);

    public static SavedViewFilters empty() {
        return new SavedViewFilters(List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null,
                null, null);
    }

    /** Listas sin nulos ni repetidos ({@code []} si no vienen) y búsqueda sin espacios sobrantes. */
    public SavedViewFilters normalized() {
        return new SavedViewFilters(distinct(state), distinct(priority), distinct(assignee), distinct(label),
                search == null || search.isBlank() ? null : search.trim(), view, sort, cycle, module, blocked,
                groupBy, type == null || type.isEmpty() ? null : distinct(type));
    }

    private static <T> List<T> distinct(Collection<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }
}
