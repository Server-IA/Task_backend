package com.taskflow.api.i18n;

import java.util.Locale;
import java.util.Map;

/**
 * Nombre de un estado para mostrarlo en un texto que redacta el backend (el reporte en PDF y su vista previa). Los
 * proyectos nacen con cinco estados que se guardan en inglés (Backlog, Todo, In Progress, Done, Cancelled): mientras
 * conserven ese nombre se ven en el idioma pedido; uno renombrado o creado por el equipo se ve como lo escribieron.
 * Es la misma regla del frontend ({@code stateName}); las respuestas JSON normales devuelven el nombre guardado.
 */
public final class StateNames {

    /** Nombre guardado en minúsculas → {inglés, español}. */
    private static final Map<String, String[]> FACTORY = Map.of(
            "backlog", new String[]{"Backlog", "Pendiente"},
            "todo", new String[]{"Todo", "Sin empezar"},
            "in progress", new String[]{"In Progress", "En curso"},
            "done", new String[]{"Done", "Completado"},
            "cancelled", new String[]{"Cancelled", "Cancelado"});

    private StateNames() {
    }

    public static String display(String storedName, Language language) {
        if (storedName == null) {
            return null;
        }
        String[] names = FACTORY.get(storedName.strip().toLowerCase(Locale.ROOT));
        if (names == null) {
            return storedName;
        }
        return language == Language.EN ? names[0] : names[1];
    }
}
