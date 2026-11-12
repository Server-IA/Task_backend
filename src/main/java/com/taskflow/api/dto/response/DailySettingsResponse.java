package com.taskflow.api.dto.response;

import java.util.List;

/**
 * Ajustes de la daily de un proyecto.
 *
 * @param sections qué bloques se exponen en la daily
 * @param agenda   puntos fijos a tratar, en orden
 */
public record DailySettingsResponse(
        Sections sections,
        List<String> agenda
) {

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
