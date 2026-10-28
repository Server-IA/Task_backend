package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.IssuePriority;
import io.swagger.v3.oas.annotations.Parameter;

import java.util.List;
import java.util.UUID;

/**
 * Filtros del listado de issues. Cada filtro acepta varios valores ({@code ?state=a&state=b}),
 * y los filtros distintos se combinan con AND.
 */
public record IssueFilter(
        @Parameter(description = "Id(s) de estado")
        List<UUID> state,

        @Parameter(description = "Prioridad(es)")
        List<IssuePriority> priority,

        @Parameter(description = "Id(s) de usuario asignado")
        List<UUID> assignee,

        @Parameter(description = "Id(s) de etiqueta")
        List<UUID> label,

        @Parameter(description = "Texto en el título o código del issue (ej. «login» o «WEB-12»)")
        String search,

        @Parameter(description = "Id del ciclo")
        UUID cycle,

        @Parameter(description = "Id del módulo")
        UUID module,

        @Parameter(description = "true = solo las bloqueadas (con al menos un bloqueo abierto, de cualquier proyecto); "
                + "false = solo las que no lo están")
        Boolean blocked
) {
}
