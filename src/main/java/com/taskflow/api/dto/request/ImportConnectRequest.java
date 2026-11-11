package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.ImportSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Conectar con la herramienta de origen. Las credenciales no se guardan: se usan para leer y se olvidan al terminar.
 *
 * @param baseUrl la dirección del sitio (solo Jira: {@code https://miempresa.atlassian.net})
 * @param email   el correo de la cuenta (Jira) o la clave de la aplicación (Trello)
 * @param token   el token o la clave de API
 */
public record ImportConnectRequest(
        @NotNull(message = "El origen es obligatorio")
        ImportSourceType source,
        @Size(max = 300, message = "La dirección es demasiado larga")
        String baseUrl,
        @Size(max = 300, message = "El correo es demasiado largo")
        String email,
        @NotBlank(message = "El token es obligatorio")
        @Size(max = 2000, message = "El token es demasiado largo")
        String token
) {
}
