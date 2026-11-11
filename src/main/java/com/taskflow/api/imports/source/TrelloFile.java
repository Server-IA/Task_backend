package com.taskflow.api.imports.source;

import com.taskflow.api.imports.model.ImportBundle;

/**
 * El archivo JSON que exporta un tablero de Trello (Menú → Imprimir, exportar y compartir → Exportar como JSON).
 */
public final class TrelloFile {

    public static final String NOT_A_BOARD = "Ese archivo no es la exportación JSON de un tablero de Trello";

    private TrelloFile() {
    }

    public static ImportBundle toBundle(byte[] file) {
        throw new SourceException(NOT_A_BOARD);
    }
}
