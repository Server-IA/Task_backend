package com.taskflow.api.imports;

import com.taskflow.api.imports.csv.CsvField;

import java.util.List;
import java.util.UUID;

/**
 * Lo que la persona eligió traer: qué proyectos del origen y dónde queda cada uno, y las opciones.
 *
 * @param projects los proyectos elegidos (los del origen que no estén aquí no se importan)
 * @param csv      solo con un archivo CSV: qué es cada columna
 */
public record ImportSelection(List<ProjectChoice> projects, Options options, CsvSetup csv) {

    /**
     * @param externalId      el proyecto en el origen
     * @param targetProjectId el proyecto de TaskFlow al que se agrega; {@code null} = se crea uno nuevo
     * @param name            nombre del proyecto nuevo
     * @param identifier      identificador del proyecto nuevo («PORT»)
     */
    public record ProjectChoice(String externalId, UUID targetProjectId, String name, String identifier) {

        public boolean createsProject() {
            return targetProjectId == null;
        }
    }

    /**
     * @param keepNumbers   conservar el número de cada tarea (PORT-123 sigue siendo PORT-123); solo en proyectos nuevos
     * @param completed     traer también lo ya terminado o cancelado
     * @param comments      traer los comentarios
     * @param attachments   traer los adjuntos
     * @param closedSprints traer los sprints cerrados (como ciclos cerrados)
     * @param archived      traer lo archivado
     */
    public record Options(boolean keepNumbers, boolean completed, boolean comments, boolean attachments, boolean closedSprints,
                          boolean archived) {

        public static final Options DEFAULTS = new Options(true, true, true, true, true, false);
    }

    /**
     * @param columns el campo de cada columna del archivo, en su orden ({@link CsvField#IGNORE} = no se importa)
     */
    public record CsvSetup(List<CsvField> columns) {
    }

    public Options optionsOrDefault() {
        return options != null ? options : Options.DEFAULTS;
    }
}
