package com.taskflow.api.util;

import com.taskflow.api.util.DescriptionTasks.Diff;
import com.taskflow.api.util.DescriptionTasks.Toggle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionTasksTest {

    @Test
    void marcarUnaCasillaEsSoloUnCambioDeMarca() {
        Diff diff = DescriptionTasks.diff("## Pasos\n- [ ] Uno\n- [ ] Dos", "## Pasos\n- [ ] Uno\n- [x] Dos");

        assertThat(diff.onlyToggles()).isTrue();
        assertThat(diff.toggles()).containsExactly(new Toggle("Dos", true));
    }

    @Test
    void desmarcarVariasALaVez() {
        Diff diff = DescriptionTasks.diff("- [x] Uno\n- [X] Dos\n- [ ] Tres", "- [ ] Uno\n- [ ] Dos\n- [ ] Tres");

        assertThat(diff.onlyToggles()).isTrue();
        assertThat(diff.toggles()).containsExactly(new Toggle("Uno", false), new Toggle("Dos", false));
    }

    @Test
    void reconoceCasillasAnidadasNumeradasYEnCitas() {
        String before = "1. [ ] Primero\n   - [ ] Anidada\n> * [ ] En una cita\n+ [ ] Con más";
        String after = "1. [x] Primero\n   - [x] Anidada\n> * [x] En una cita\n+ [x] Con más";

        Diff diff = DescriptionTasks.diff(before, after);

        assertThat(diff.onlyToggles()).isTrue();
        assertThat(diff.toggles()).extracting(Toggle::text)
                .containsExactly("Primero", "Anidada", "En una cita", "Con más");
    }

    @Test
    void elTextoEsElMarkdownDeLaLinea() {
        Diff diff = DescriptionTasks.diff("- [ ] **Revisar** WEB-12 con @[Ana](x)", "- [x] **Revisar** WEB-12 con @[Ana](x)");

        assertThat(diff.toggles()).containsExactly(new Toggle("**Revisar** WEB-12 con @[Ana](x)", true));
    }

    @Test
    void dentroDeUnBloqueDeCodigoNoHayCasillas() {
        String before = "```\n- [ ] no es casilla\n```\n- [ ] Sí";
        String after = "```\n- [x] no es casilla\n```\n- [ ] Sí";

        Diff diff = DescriptionTasks.diff(before, after);

        // Cambió el código: es una edición, sin casillas marcadas
        assertThat(diff.onlyToggles()).isFalse();
        assertThat(diff.toggles()).isEmpty();
    }

    @Test
    void unBloqueCerradoConMasComillasTambienTermina() {
        Diff diff = DescriptionTasks.diff("````\n```\n````\n- [ ] Fuera", "````\n```\n````\n- [x] Fuera");

        assertThat(diff.onlyToggles()).isTrue();
        assertThat(diff.toggles()).containsExactly(new Toggle("Fuera", true));
    }

    @Test
    void editarElTextoYMarcarEsUnaEdicionConLaCasillaMarcada() {
        Diff diff = DescriptionTasks.diff("- [ ] Uno\n- [ ] Dos", "Intro nueva\n\n- [ ] Dos\n- [x] Uno");

        assertThat(diff.onlyToggles()).isFalse();
        assertThat(diff.toggles()).containsExactly(new Toggle("Uno", true));
    }

    @Test
    void unaCasillaNuevaYaMarcadaNoEsMarcarla() {
        Diff diff = DescriptionTasks.diff("- [ ] Uno", "- [ ] Uno\n- [x] Nueva");

        assertThat(diff.onlyToggles()).isFalse();
        assertThat(diff.toggles()).isEmpty();
    }

    @Test
    void cambiarElTextoDeUnaCasillaEsEditar() {
        Diff diff = DescriptionTasks.diff("- [ ] Uno", "- [ ] Uno corregido");

        assertThat(diff.onlyToggles()).isFalse();
        assertThat(diff.toggles()).isEmpty();
    }

    @Test
    void lasCasillasVaciasNoSeRegistran() {
        Diff diff = DescriptionTasks.diff("- [ ]\n- [ ] Uno", "- [x]\n- [ ] Uno");

        assertThat(diff.toggles()).isEmpty();
        // Sin entrada propia, el cambio queda como «editó la descripción» (no se pierde del historial)
        assertThat(diff.onlyToggles()).isFalse();
    }

    @Test
    void unaCasillaVaciaJuntoAUnaConTextoSoloRegistraLaDeTexto() {
        Diff diff = DescriptionTasks.diff("- [ ]\n- [ ] Uno", "- [x]\n- [x] Uno");

        assertThat(diff.onlyToggles()).isTrue();
        assertThat(diff.toggles()).containsExactly(new DescriptionTasks.Toggle("Uno", true));
    }

    @Test
    void sinDescripcionNoHayCasillas() {
        assertThat(DescriptionTasks.diff(null, "- [x] Nueva")).isEqualTo(new Diff(false, List.of()));
        assertThat(DescriptionTasks.diff("- [x] Vieja", null)).isEqualTo(new Diff(false, List.of()));
    }

    @Test
    void unTextoLargoSeCorta() {
        String longText = "a".repeat(400);
        Diff diff = DescriptionTasks.diff("- [ ] " + longText, "- [x] " + longText);

        assertThat(diff.toggles().getFirst().text()).hasSize(DescriptionTasks.MAX_TEXT).endsWith("…");
    }

    @Test
    void losCorchetesSinViñetaNoSonCasillas() {
        Diff diff = DescriptionTasks.diff("[ ] suelto", "[x] suelto");

        assertThat(diff.onlyToggles()).isFalse();
        assertThat(diff.toggles()).isEmpty();
    }
}
