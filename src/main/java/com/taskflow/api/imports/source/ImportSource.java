package com.taskflow.api.imports.source;

import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;

import java.util.List;

/**
 * Una herramienta de la que se importa por conexión directa. Solo lee: comprueba las credenciales y dice qué proyectos
 * hay, trae los elegidos en el formato común y descarga un adjunto. Los errores que la persona puede arreglar (token
 * malo, dirección equivocada, sin permiso) salen como {@link SourceException}, con un mensaje para mostrarle.
 */
public interface ImportSource {

    ImportSourceType type();

    /**
     * @param account  con qué cuenta se entró, para confirmárselo a la persona
     * @param label    de dónde, como se le muestra («miempresa.atlassian.net»)
     * @param projects lo que esa cuenta puede ver
     */
    record Discovery(String account, String label, List<Found> projects) {
    }

    /**
     * @param key        la clave corta del proyecto en el origen, si la tiene
     * @param issueCount cuántas tareas tiene ({@code -1} si el origen no lo dice sin leerlas)
     */
    record Found(String id, String key, String name, int issueCount) {
    }

    /** Avisa por dónde va la lectura (para mostrar el avance). */
    interface Progress {
        void read(int done, int total);
    }

    Discovery discover(SourceConnection connection);

    /**
     * @param projectIds   los proyectos elegidos ({@link Found#id})
     * @param withComments si hay que traer los comentarios (en algunos orígenes cuestan una petición más por tarea)
     */
    ImportBundle fetch(SourceConnection connection, List<String> projectIds, boolean withComments, Progress progress);

    byte[] download(SourceConnection connection, ExtAttachment attachment);
}
