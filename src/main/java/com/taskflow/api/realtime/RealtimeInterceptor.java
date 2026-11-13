package com.taskflow.api.realtime;

import com.taskflow.api.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Después de cada cambio que se hizo bien por la API (POST, PUT, PATCH o DELETE con respuesta 2xx), avisa en tiempo
 * real a quien puede verlo. Lo que cambió se lee de la propia ruta: {@code /workspaces/{slug}/projects/{projectId}/
 * issues/{issueId}/…}. Así cualquier endpoint nuevo avisa solo, sin acordarse de llamar al {@link RealtimeHub}.
 * Lo que no pasa por una petición (los avisos de GitHub, las importaciones, las notificaciones) avisa desde su
 * servicio.
 */
@Component
@RequiredArgsConstructor
public class RealtimeInterceptor implements HandlerInterceptor {

    /** La pestaña que hizo el cambio: se le devuelve en el aviso para que no vuelva a pedir lo que ya tiene. */
    public static final String CLIENT_HEADER = "X-Client-Id";

    private static final Set<String> CHANGES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final RealtimeHub hub;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || response.getStatus() >= 400 || !CHANGES.contains(request.getMethod())) {
            return;
        }
        String origin = clientId(request);
        if (request.getRequestURI().startsWith("/api/v1/notifications")) {
            // Marcar como leída en una pestaña: las demás de esa persona actualizan su contador
            SecurityUtils.currentUserId().ifPresent(userId -> hub.notifications(userId, origin));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String slug = variables == null ? null : variables.get("slug");
        if (slug == null) {
            // Crear un workspace, el perfil, el login…: no hay a quién más contarle
            return;
        }
        UUID projectId = uuid(variables.get("projectId"));
        if (projectId == null) {
            hub.workspace(slug, origin);
        } else {
            hub.project(slug, projectId, uuid(variables.get("issueId")), origin);
        }
    }

    static String clientId(HttpServletRequest request) {
        String value = request.getHeader(CLIENT_HEADER);
        return value != null && CLIENT_ID.matcher(value).matches() ? value : null;
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
