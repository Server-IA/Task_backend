package com.taskflow.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Cerrar un ciclo. Las tareas sin terminar (ni completadas ni canceladas) van a donde diga {@code moveTo}, salvo
 * las que tengan una excepción en {@code overrides}. {@code targetCycleId} elige el ciclo destino (por defecto, el
 * siguiente). {@code startRetro} abre la retrospectiva del ciclo.
 */
public record CloseCycleRequest(
        @NotNull(message = "Indica a dónde van las tareas sin terminar: NEXT_CYCLE o BACKLOG")
        CycleMoveTarget moveTo,

        UUID targetCycleId,

        @Size(max = 500, message = "Máximo 500 excepciones")
        List<@NotNull(message = "La excepción no puede ser nula") @Valid IssueMove> overrides,

        /** {@code true} abre la retrospectiva del ciclo; {@code null} = {@code false}. */
        Boolean startRetro
) {

    /** Excepción para una tarea: va a {@code moveTo} en lugar de al destino general. */
    public record IssueMove(
            @NotNull(message = "La tarea es obligatoria")
            UUID issueId,

            @NotNull(message = "Indica a dónde va la tarea: NEXT_CYCLE o BACKLOG")
            CycleMoveTarget moveTo
    ) {
    }
}
