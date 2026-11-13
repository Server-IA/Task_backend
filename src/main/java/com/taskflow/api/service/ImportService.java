package com.taskflow.api.service;

import com.taskflow.api.dto.request.ImportConnectRequest;
import com.taskflow.api.dto.request.ImportUploadRequest;
import com.taskflow.api.dto.response.ImportJobResponse;
import com.taskflow.api.imports.ImportMapping;
import com.taskflow.api.imports.ImportSelection;

import java.util.List;
import java.util.UUID;

/**
 * Importar proyectos y tareas desde otra herramienta. El recorrido: conectar (o subir un archivo) → elegir qué traer →
 * revisar las equivalencias y lo que se va a crear → importar en segundo plano → informe. Una importación terminada se
 * puede deshacer completa.
 */
public interface ImportService {

    /** Las importaciones del workspace, las más recientes primero (también las que quedaron sin terminar). */
    List<ImportJobResponse> list(String slug);

    ImportJobResponse get(String slug, UUID jobId);

    /** Entra a la herramienta de origen con esas credenciales y dice qué proyectos hay. */
    ImportJobResponse connect(String slug, UUID userId, ImportConnectRequest request);

    /** Lee el archivo y dice qué trae. */
    ImportJobResponse upload(String slug, UUID userId, ImportUploadRequest request);

    /** Guarda qué se trae y empieza a leerlo del origen (en segundo plano). */
    ImportJobResponse select(String slug, UUID jobId, UUID userId, ImportSelection selection);

    /** Guarda las equivalencias; la respuesta trae lo que se crearía con ellas. */
    ImportJobResponse saveMapping(String slug, UUID jobId, UUID userId, ImportMapping mapping);

    /** Empieza a escribir en TaskFlow (en segundo plano). */
    ImportJobResponse start(String slug, UUID jobId, UUID userId);

    /** Pide detenerla: para al terminar la tanda en curso; lo que ya entró se queda. */
    ImportJobResponse stop(String slug, UUID jobId);

    /** Borra lo que creó la importación (en segundo plano). */
    ImportJobResponse undo(String slug, UUID jobId);

    /** Descarta una importación que no llegó a escribir nada. */
    void discard(String slug, UUID jobId);

    /** El informe de lo que no entró, en CSV. */
    FileDownload report(String slug, UUID jobId);

    record FileDownload(String fileName, byte[] content) {
    }
}
