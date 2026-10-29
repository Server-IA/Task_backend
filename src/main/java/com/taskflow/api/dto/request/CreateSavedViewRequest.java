package com.taskflow.api.dto.request;

import com.taskflow.api.entity.SavedViewFilters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Guardar una vista. Sin {@code shared} es personal; una vista compartida solo la puede crear el project manager.
 * Sin {@code filters} se guarda sin filtros. Queda al final de su grupo (compartidas o personales).
 */
public record CreateSavedViewRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 40, message = "El nombre no puede superar 40 caracteres")
        String name,

        Boolean shared,

        @Valid
        SavedViewFilters filters
) {
}
