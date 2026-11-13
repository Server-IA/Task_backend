package com.taskflow.api.realtime;

import com.taskflow.api.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Tiempo real", description = "Avisos de lo que cambia, para que la pantalla se actualice sola")
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class RealtimeController {

    private final RealtimeHub hub;

    @Operation(summary = "Conexión de avisos (Server-Sent Events)",
            description = "Queda abierta hasta 30 min (el navegador vuelve a conectarse) con un comentario cada 25 s. "
                    + "Eventos: «ready» al abrirse; «change» con {workspace, projectId?, issueId?, origin?} cuando "
                    + "cambia algo que esta persona puede ver (sin projectId: el workspace entero); «notifications» "
                    + "cuando hay algo nuevo en su bandeja. Solo identificadores: los datos se piden a la API como "
                    + "siempre. origin es el X-Client-Id de la pestaña que hizo el cambio (esa ya lo sabe).")
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal UserPrincipal me, HttpServletResponse response) {
        // nginx (el del frontend) no guarda los avisos para mandarlos juntos, y nadie los cachea
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return hub.connect(me.getId());
    }
}
