package com.taskflow.api.imports.source;

/**
 * Con qué se entra a la herramienta de origen. Nunca se guarda en la base de datos: vive en memoria mientras dura la
 * importación (ver {@code ImportCredentials}).
 *
 * @param baseUrl la dirección del sitio, donde aplique (Jira: {@code https://miempresa.atlassian.net})
 * @param email   la cuenta, donde aplique (Jira); en Trello, la clave de la aplicación
 * @param token   el token o la clave de API
 */
public record SourceConnection(String baseUrl, String email, String token) {

    @Override
    public String toString() {
        // Que un registro o un error nunca lleven el token
        return "SourceConnection[" + baseUrl + "]";
    }
}
