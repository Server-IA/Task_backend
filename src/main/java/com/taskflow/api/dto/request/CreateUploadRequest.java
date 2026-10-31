package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Pedir una subida directa a R2 (URL firmada) de un archivo de una tarea.
 *
 * @param purpose     {@code ATTACHMENT} (sección «Adjuntos»: imágenes, documentos o ZIP) o {@code INLINE_IMAGE} (imagen
 *                    pegada en una descripción o un comentario: solo imágenes)
 * @param issueId     la tarea; obligatoria en un adjunto. En una imagen puede faltar mientras la tarea no existe
 *                    («Nueva tarea»): se liga a la tarea al crearla con ese Markdown
 * @param fileName    nombre original (con su extensión: decide el tipo)
 * @param contentType el tipo que declara el navegador; debe corresponder a la extensión (vacío: el de la extensión)
 * @param size        tamaño en bytes (imágenes hasta 5 MB; lo demás hasta 10 MB)
 */
public record CreateUploadRequest(
        @NotNull(message = "Indica para qué es el archivo")
        UploadPurpose purpose,

        UUID issueId,

        @NotBlank(message = "El archivo no tiene nombre")
        @Size(max = 255, message = "El nombre del archivo no puede superar 255 caracteres")
        String fileName,

        @Size(max = 100, message = "El tipo del archivo es demasiado largo")
        String contentType,

        @NotNull(message = "Falta el tamaño del archivo")
        @Positive(message = "El archivo está vacío")
        Long size
) {

    public enum UploadPurpose {
        ATTACHMENT,
        INLINE_IMAGE
    }
}
