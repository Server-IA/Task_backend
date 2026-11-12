package com.taskflow.api.imports;

import java.util.List;

/**
 * Por dónde va una importación: sus etapas, cada una con cuánto lleva de cuánto, y en qué está ahora.
 *
 * @param current lo que se está escribiendo en este momento («PORT-212 · Exportar el historial»), o {@code null}
 */
public record ImportProgress(List<Stage> stages, String current) {

    public enum StageKey {
        /** Leer del origen lo elegido. */
        FETCH,
        PROJECTS, ISSUES, COMMENTS, ATTACHMENTS, RELATIONS
    }

    public record Stage(StageKey key, int done, int total) {
    }

    /** Avance total, de 0 a 100, contando cada cosa por igual. */
    public int percent() {
        long done = 0;
        long total = 0;
        for (Stage stage : stages) {
            done += Math.min(stage.done(), stage.total());
            total += stage.total();
        }
        return total == 0 ? 0 : (int) Math.min(100, done * 100 / total);
    }
}
