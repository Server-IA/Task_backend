package com.taskflow.api.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Ajustes de la daily de un proyecto.
 *
 * @param secondsPerPerson tiempo sugerido por persona en «Presentar», en segundos (por defecto 120)
 * @param personTimes      personas con un tiempo distinto al general (solo quienes siguen en el workspace)
 * @param sections         qué bloques se exponen en la daily
 * @param agenda           puntos fijos a tratar, en orden
 */
public record DailySettingsResponse(
        int secondsPerPerson,
        List<PersonTime> personTimes,
        Sections sections,
        List<String> agenda
) {

    /** Tiempo propio de una persona, en segundos. */
    public record PersonTime(UUID userId, int seconds) {
    }

    /**
     * @param summary   la franja «desde ayer» con los contadores del proyecto
     * @param yesterday el bloque «Ayer» de cada persona
     * @param today     el bloque «Hoy» de cada persona
     * @param blockers  el bloque «Bloqueos» de cada persona
     * @param note      la nota del día
     */
    public record Sections(boolean summary, boolean yesterday, boolean today, boolean blockers, boolean note) {
    }
}
