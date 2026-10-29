package com.taskflow.api.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Página de resultados con un formato estable para el frontend.
 * <p>
 * Los listados que admiten cursor (la bandeja y el feed de actividad) traen además {@code nextCursor}: el valor que
 * se pasa como {@code before} para pedir la página siguiente, o {@code null} si no hay más. Con {@code before}, la
 * página no se cuenta: {@code page} es 0 y {@code totalElements} y {@code totalPages} son -1 (desconocidos).
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        String nextCursor
) {

    /** Total desconocido (página pedida con cursor). */
    public static final int UNKNOWN = -1;

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page, mapper, null);
    }

    /**
     * @param cursorOf el cursor de un elemento; {@code null} si el listado no admite cursor (o no con este orden)
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper, Function<E, String> cursorOf) {
        String nextCursor = cursorOf != null && page.hasNext() && page.hasContent()
                ? cursorOf.apply(page.getContent().getLast())
                : null;
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                nextCursor);
    }

    /**
     * Página pedida con cursor. {@code rows} trae hasta {@code size + 1} elementos: el de más solo indica que hay otra
     * página y no se devuelve.
     */
    public static <E, T> PageResponse<T> fromCursor(List<E> rows, int size, Function<E, T> mapper,
                                                    Function<E, String> cursorOf) {
        boolean hasMore = rows.size() > size;
        List<E> content = hasMore ? rows.subList(0, size) : rows;
        return new PageResponse<>(
                content.stream().map(mapper).toList(),
                0,
                size,
                UNKNOWN,
                UNKNOWN,
                false,
                !hasMore,
                hasMore ? cursorOf.apply(content.getLast()) : null);
    }
}
