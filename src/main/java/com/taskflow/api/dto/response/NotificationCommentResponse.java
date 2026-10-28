package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * Extracto del comentario de una notificación.
 *
 * @param excerpt el texto del comentario en una línea, con las menciones como «@Nombre», cortado con «…» si supera
 *                200 caracteres
 */
public record NotificationCommentResponse(
        UUID id,
        String excerpt
) {
}
