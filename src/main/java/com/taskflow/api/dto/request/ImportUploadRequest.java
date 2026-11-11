package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.ImportSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Importar desde un archivo que exporta la herramienta de origen: CSV (cualquiera) o el JSON de un tablero de Trello.
 *
 * @param source        de qué herramienta viene ({@code CSV} si es de otra o de una hoja de cálculo)
 * @param fileName      el nombre del archivo
 * @param contentBase64 el archivo, en Base64 (así llega tal cual, sea cual sea su codificación)
 */
public record ImportUploadRequest(
        @NotNull(message = "El origen es obligatorio")
        ImportSourceType source,
        @NotBlank(message = "El nombre del archivo es obligatorio")
        @Size(max = 200, message = "El nombre del archivo es demasiado largo")
        String fileName,
        @NotBlank(message = "El archivo está vacío")
        String contentBase64
) {
}
