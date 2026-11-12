package com.taskflow.api.imports.source;

/**
 * La herramienta de origen no dejó leer: credenciales malas, dirección equivocada, sin permiso, límite de peticiones o
 * caída. El mensaje es para la persona (en español, como todos; se traduce al responder).
 */
public class SourceException extends RuntimeException {

    public SourceException(String message) {
        super(message);
    }

    public SourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
