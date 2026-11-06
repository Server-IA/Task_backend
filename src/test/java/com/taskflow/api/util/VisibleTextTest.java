package com.taskflow.api.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Un texto de puros espacios invisibles cuenta como vacío (antes se guardaba una tarea o un comentario «vacío»). */
class VisibleTextTest {

    @Test
    void losEspaciosDurosYDeAnchoCeroCuentanComoVacios() {
        for (String invisible : new String[] {" ", " ", " ", "\u0085", "​", "⁠", "﻿",
                "　", "  \t\n​ "}) {
            assertThat(VisibleText.isBlank(invisible)).as("U+%04X", (int) invisible.charAt(0)).isTrue();
            assertThat(VisibleText.strip(invisible)).isEmpty();
        }
        assertThat(VisibleText.isBlank(null)).isTrue();
        assertThat(VisibleText.isBlank("")).isTrue();
    }

    @Test
    void recortaSoloLosBordesYConservaElTexto() {
        assertThat(VisibleText.strip(" ​ Revisar  el login ﻿")).isEqualTo("Revisar  el login");
        assertThat(VisibleText.strip("Sin cambios")).isEqualTo("Sin cambios");
        assertThat(VisibleText.strip(null)).isNull();
        assertThat(VisibleText.isBlank(" x")).isFalse();
    }

    @Test
    void losDeUnionNoSonEspacios() {
        // Arman emojis compuestos (mujer + ZWJ + computador)
        String emoji = "👩‍💻";
        assertThat(VisibleText.strip(" " + emoji + " ")).isEqualTo(emoji);
        assertThat(VisibleText.isBlank("‍")).isFalse();
    }

    @Test
    void unTextoLargoLlenoDeEspaciosSeRecorreEnTiempoLineal() {
        String text = "x" + " ".repeat(200_000) + "y" + " ".repeat(200_000);
        long start = System.nanoTime();
        assertThat(VisibleText.strip(text)).hasSize(200_002);
        assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
    }
}
