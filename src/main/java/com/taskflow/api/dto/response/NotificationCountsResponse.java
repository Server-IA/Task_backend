package com.taskflow.api.dto.response;

/**
 * Contadores de la bandeja.
 *
 * @param unread         notificaciones sin leer (pestaña «No leídas»)
 * @param unreadMentions menciones ({@code ISSUE_MENTIONED}) sin leer
 * @param mentions       menciones en total, leídas o no (pestaña «Menciones»)
 */
public record NotificationCountsResponse(
        long unread,
        long unreadMentions,
        long mentions
) {
}
