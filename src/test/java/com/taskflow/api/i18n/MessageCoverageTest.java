package com.taskflow.api.i18n;

import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.entity.enums.PermissionGroup;
import com.taskflow.api.i18n.SourceMessages.Found;
import com.taskflow.api.i18n.SourceMessages.Kind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La red que evita que un mensaje nuevo se quede sin inglés: recorre {@code src/main/java}, saca los mensajes de las
 * excepciones de la API, los de validación y los demás textos en español ({@link SourceMessages}) y falla si alguno
 * no tiene traducción en {@code i18n/en.json}, exacta o por patrón.
 * <p>
 * Si falla con un mensaje nuevo: agrega su traducción a {@code src/main/resources/i18n/en.json} (en {@code exact}, o
 * un patrón en {@code patterns} si tiene partes variables). Si el texto no llega a nadie o se deja en español a
 * propósito, agrégalo a {@link #NOT_TRANSLATED} diciendo por qué.
 */
class MessageCoverageTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** Textos en español del código que no se traducen, con la razón. */
    private static final Set<String> NOT_TRANSLATED = Set.of(
            // Documentación de la API (Swagger), no una respuesta
            "API REST del gestor de tareas TaskFlow. Inicia sesión en /api/v1/auth/login y pega el accessToken en «Authorize».",
            // Lo guarda el historial de la tarea y el frontend lo lee tal cual («bloquea WEB-1», «relacionada con…»)
            "bloqueada por ", "relacionada con ",
            // Historial de una historia que TaskFlow movió sola («Luis empezó WEB-41»): se guarda así
            " terminó ", " empezó ",
            // Daily: el frontend distingue el cambio con `kind` y arma su propio texto; `change` queda en español
            "completó", "movió a ", "creó", "comentó", "actualizó",
            // Cómo se reconoce un navegador (no es un texto que se muestre)
            "mac os");

    /** Archivos cuyos textos en español son datos y no mensajes, con la razón. */
    private static final Set<String> NOT_TRANSLATED_FILES = Set.of(
            // Plantillas que trae un proyecto nuevo (se guardan y el equipo las edita)
            "DefaultTemplates.java",
            // Consultas JPQL
            "NotificationRepository.java",
            // Los nombres de los estados de fábrica en cada idioma: son la tabla de la traducción
            "StateNames.java",
            // Importar: los nombres con los que otras herramientas llaman a sus columnas, estados, tipos y prioridades
            // (se comparan con lo que trae el archivo o el origen; nunca se muestran)
            "CsvField.java", "CsvImport.java", "CsvDates.java", "ImportDefaults.java", "JiraSource.java",
            // Importar: consultas SQL
            "ImportStore.java");

    private static List<Found> found;

    @BeforeAll
    static void scan() {
        found = SourceMessages.scan(MAIN).stream()
                .filter(message -> NOT_TRANSLATED_FILES.stream().noneMatch(file -> message.file().endsWith("/" + file)))
                .toList();
    }

    @Test
    void todosLosMensajesDelCodigoTienenTraduccionAlIngles() {
        Set<String> missing = new TreeSet<>();
        for (Found message : found) {
            if (!NOT_TRANSLATED.contains(message.text()) && !Translations.hasTranslation(message.text())) {
                missing.add(message.text() + "   ← " + message.file() + ":" + message.line());
            }
        }
        assertThat(missing)
                .as("Mensajes sin traducción al inglés: agrégalos a src/main/resources/i18n/en.json")
                .isEmpty();
    }

    @Test
    void elRecorridoEncuentraLosMensajesDeExcepcionesValidacionYConPartesVariables() {
        // Si el recorrido dejara de encontrar mensajes, la prueba de arriba pasaría sin comprobar nada
        assertThat(found.stream().filter(message -> message.kind() == Kind.EXCEPTION).count()).isGreaterThan(120);
        assertThat(found.stream().filter(message -> message.kind() == Kind.VALIDATION).count()).isGreaterThan(150);
        assertThat(found).extracting(Found::text).contains(
                "Tarea no encontrada",
                "El correo es obligatorio",
                "No puede estar vacío",
                "Demasiados intentos. Intenta de nuevo en 7 segundos",
                "Falta el parámetro '7'",
                "Ya hay una vista compartida llamada «7» en este proyecto",
                "Tu rol no permite subir adjuntos",
                "Debes iniciar sesión");
    }

    @Test
    void loQueNoSeTraduceSigueExistiendoEnElCodigo() {
        // Una excepción que ya no hace falta se quita de la lista
        List<String> texts = found.stream().map(Found::text).toList();
        List<String> stale = new ArrayList<>(NOT_TRANSLATED);
        stale.removeAll(texts);
        assertThat(stale).as("Textos de NOT_TRANSLATED que ya no están en el código").isEmpty();
    }

    @Test
    void elCatalogoDePermisosTieneTraduccion() {
        // Las etiquetas de una sola palabra («Estados», «Plantillas») no parecen frases: se comprueban aparte
        for (PermissionGroup group : PermissionGroup.values()) {
            assertThat(Translations.hasTranslation(group.getLabel())).as(group.getLabel()).isTrue();
        }
        for (Permission permission : Permission.values()) {
            assertThat(Translations.hasTranslation(permission.getLabel())).as(permission.getLabel()).isTrue();
            assertThat(Translations.hasTranslation(permission.getDescription())).as(permission.getDescription()).isTrue();
        }
    }
}
