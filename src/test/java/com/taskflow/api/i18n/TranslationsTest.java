package com.taskflow.api.i18n;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El traductor (el español es la clave), el idioma de una petición y los nombres de los estados de fábrica.
 */
class TranslationsTest {

    @Test
    void enEspanolElTextoQuedaComoEsta() {
        assertThat(Language.ES.t("Tarea no encontrada")).isEqualTo("Tarea no encontrada");
        assertThat(Language.ES.t(null)).isNull();
    }

    @Test
    void traduceUnMensajeFijo() {
        assertThat(Language.EN.t("Tarea no encontrada")).isEqualTo("Task not found");
        assertThat(Language.EN.t("Debes verificar tu correo antes de iniciar sesión"))
                .isEqualTo("You must verify your email before logging in");
    }

    @Test
    void traduceUnMensajeConPartesVariables() {
        assertThat(Language.EN.t("Demasiados intentos. Intenta de nuevo en 34 segundos"))
                .isEqualTo("Too many attempts. Try again in 34 seconds");
        assertThat(Language.EN.t("Ya hay un rol llamado «Líder»")).isEqualTo("There is already a role named “Líder”");
        assertThat(Language.EN.t("Valentina Ríos te asignó PORT-8"))
                .isEqualTo("Valentina Ríos assigned you PORT-8");
    }

    @Test
    void elPatronMasEspecificoGana() {
        // «X ya bloquea a Y» no se traga el mensaje más largo que empieza igual
        assertThat(Language.EN.t("WEB-1 ya bloquea a WEB-2")).isEqualTo("WEB-1 already blocks WEB-2");
        assertThat(Language.EN.t("WEB-1 ya bloquea a WEB-2: dos tareas no pueden bloquearse mutuamente"))
                .isEqualTo("WEB-1 already blocks WEB-2: two tasks cannot block each other");
        assertThat(Language.EN.t("Mateo te mencionó en la descripción de PORT-3"))
                .isEqualTo("Mateo mentioned you in the description of PORT-3");
    }

    @Test
    void unFragmentoQueTambienEsTextoDelCodigoSeTraduceAdentro() {
        assertThat(Language.EN.t("Tu rol no permite editar las vistas compartidas"))
                .isEqualTo("Your role does not allow you to edit shared views");
        assertThat(Language.EN.t("En fecha límite, «desde» no puede ser posterior a «hasta»"))
                .isEqualTo("For due date, “from” cannot be after “to”");
    }

    @Test
    void loQueNoTieneTraduccionQuedaEnEspanol() {
        assertThat(Language.EN.t("Un texto que nadie tradujo")).isEqualTo("Un texto que nadie tradujo");
        assertThat(Translations.hasTranslation("Un texto que nadie tradujo")).isFalse();
    }

    @Test
    void traduceCadaErrorDeCampoConservandoElOrden() {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("title", "El título es obligatorio");
        errors.put("email", "El correo no es válido");

        Map<String, String> translated = Translations.translate(errors, Language.EN);

        assertThat(translated).containsExactly(
                Map.entry("title", "The title is required"),
                Map.entry("email", "The email is not valid"));
        assertThat(Translations.translate(errors, Language.ES)).isSameAs(errors);
    }

    @Test
    void elIdiomaDeLaPeticionSaleDeLaCabecera() {
        assertThat(Language.fromHeader("en")).isEqualTo(Language.EN);
        assertThat(Language.fromHeader("en-US,en;q=0.9,es;q=0.8")).isEqualTo(Language.EN);
        assertThat(Language.fromHeader("es-CO")).isEqualTo(Language.ES);
        assertThat(Language.fromHeader("fr")).isEqualTo(Language.ES);
        assertThat(Language.fromHeader(null)).isEqualTo(Language.ES);
        assertThat(Language.of(Locale.UK)).isEqualTo(Language.EN);
        assertThat(Language.fromCode("en")).isEqualTo(Language.EN);
        assertThat(Language.fromCode("fr")).isNull();
    }

    @Test
    void losEstadosDeFabricaSeVenEnElIdiomaPedidoYLosPropiosComoSeEscribieron() {
        assertThat(StateNames.display("Todo", Language.ES)).isEqualTo("Sin empezar");
        assertThat(StateNames.display("in progress", Language.ES)).isEqualTo("En curso");
        assertThat(StateNames.display("Backlog", Language.ES)).isEqualTo("Pendiente");
        assertThat(StateNames.display("Done", Language.ES)).isEqualTo("Completado");
        assertThat(StateNames.display("Cancelled", Language.ES)).isEqualTo("Cancelado");
        assertThat(StateNames.display("Todo", Language.EN)).isEqualTo("Todo");
        assertThat(StateNames.display("In Review", Language.ES)).isEqualTo("In Review");
        assertThat(StateNames.display(null, Language.EN)).isNull();
    }
}
