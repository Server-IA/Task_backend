package com.taskflow.api.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextTest {

    @Test
    void conservaLasTildesLaEnieYLasComillasEspanolas() {
        assertThat(PdfText.clean("Diseñó «la sesión» – revisión… ¿ya?")).isEqualTo("Diseñó «la sesión» – revisión… ¿ya?");
    }

    @Test
    void reemplazaFlechasYVistosPorAlgoLegible() {
        assertThat(PdfText.clean("In Review → Done")).isEqualTo("In Review -> Done");
        assertThat(PdfText.clean("✓ listo")).isEqualTo("[x] listo");
    }

    @Test
    void quitaEmojisYOtrosAlfabetosSinDejarDobleEspacio() {
        assertThat(PdfText.clean("Lanzar 🚀 la app")).isEqualTo("Lanzar la app");
        assertThat(PdfText.clean("日本語 título")).isEqualTo("título");
    }

    @Test
    void dejaElTextoEnUnaLineaYSinEspaciosInvisibles() {
        assertThat(PdfText.clean("  uno\n\tdos tres​ ")).isEqualTo("uno dos tres");
    }

    @Test
    void nullEsVacio() {
        assertThat(PdfText.clean(null)).isEmpty();
    }
}
