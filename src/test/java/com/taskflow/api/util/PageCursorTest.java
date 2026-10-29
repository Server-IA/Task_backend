package com.taskflow.api.util;

import com.taskflow.api.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El cursor {@code <createdAt>,<id>} va y vuelve sin perder precisión.
 */
class PageCursorTest {

    @Test
    void idaYVuelta() {
        Instant at = Instant.parse("2026-10-29T14:03:07.123456Z");
        UUID id = UUID.randomUUID();

        String text = PageCursor.format(at, id);
        PageCursor cursor = PageCursor.parse(text);

        assertThat(text).isEqualTo("2026-10-29T14:03:07.123456Z," + id);
        assertThat(cursor.createdAt()).isEqualTo(at);
        assertThat(cursor.id()).isEqualTo(id);
        assertThat(cursor.toString()).isEqualTo(text);
    }

    @Test
    void vacioEsSinCursor() {
        assertThat(PageCursor.parse(null)).isNull();
        assertThat(PageCursor.parse("  ")).isNull();
    }

    @Test
    void malFormadoEs400() {
        for (String bad : new String[] {"x", "2026-10-29T14:03:07Z", "2026-10-29T14:03:07Z,", ",6f1c0d2e",
                "ayer," + UUID.randomUUID(), "2026-10-29T14:03:07Z,no-es-uuid"}) {
            assertThatThrownBy(() -> PageCursor.parse(bad)).as(bad).isInstanceOf(BadRequestException.class);
        }
    }
}
