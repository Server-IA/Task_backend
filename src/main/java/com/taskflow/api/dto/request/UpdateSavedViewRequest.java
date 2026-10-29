package com.taskflow.api.dto.request;

import com.taskflow.api.entity.SavedViewFilters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Edición parcial: los campos nulos no se modifican. {@code filters} reemplaza todos los filtros.
 * {@code position} es el nuevo índice (desde 0) dentro de su grupo: las compartidas del proyecto o las personales
 * del dueño. Al cambiar {@code shared}, la vista pasa al final del otro grupo.
 */
public record UpdateSavedViewRequest(
        @Size(min = 1, max = 40, message = "El nombre debe tener entre 1 y 40 caracteres")
        String name,

        @Valid
        SavedViewFilters filters,

        Boolean shared,

        @Min(value = 0, message = "La posición no puede ser negativa")
        Integer position
) {
}
