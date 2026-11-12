package com.taskflow.api.imports.source;

import com.taskflow.api.imports.ImportJson;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El archivo JSON que exporta un tablero de Trello (Menú → Imprimir, exportar y compartir → Exportar como JSON). Es el
 * mismo tablero que da la API, con dos límites que pone Trello: solo trae las últimas 1000 acciones, así que a un
 * tablero con mucha historia le faltan los comentarios más viejos, y los adjuntos van nombrados pero no se pueden
 * descargar sin entrar con una cuenta (para eso está {@link TrelloSource}).
 */
public final class TrelloFile {

    public static final String NOT_A_BOARD = "Ese archivo no es la exportación JSON de un tablero de Trello";

    private TrelloFile() {
    }

    public static ImportBundle toBundle(byte[] file) {
        JsonNode board;
        try {
            board = ImportJson.MAPPER.readTree(file);
        } catch (JacksonException ex) {
            throw new SourceException(NOT_A_BOARD, ex);
        }
        // Cualquier otro JSON (el de una tarjeta, el de otra herramienta) no tiene las dos cosas
        if (board == null || !board.isObject() || !board.path("lists").isArray() || !board.path("cards").isArray()) {
            throw new SourceException(NOT_A_BOARD);
        }
        Map<String, ExtUser> users = new LinkedHashMap<>();
        ExtProject project = TrelloBoard.project(board, board.path("actions"), false, users);
        return new ImportBundle(List.of(project), List.copyOf(users.values()));
    }
}
