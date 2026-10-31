package com.taskflow.api.entity.enums;

/**
 * Para qué se subió un archivo ({@code StoredFile}).
 */
public enum FileKind {
    /** Adjunto de una tarea: aparece en «Adjuntos» y cuenta para el clip de las tarjetas. */
    ATTACHMENT,
    /**
     * Imagen pegada (o soltada) en una descripción o un comentario: va dentro del Markdown como
     * {@code ![nombre](/files/<id>)}. No aparece en «Adjuntos» ni cuenta para el clip.
     */
    INLINE_IMAGE,
    /** Foto de perfil (512 × 512, recortada en el navegador). No es de ningún workspace. */
    AVATAR
}
