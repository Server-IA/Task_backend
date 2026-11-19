package com.taskflow.api.realtime;

import com.taskflow.api.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RealtimeInterceptorTest {

    private final RealtimeHub hub = mock(RealtimeHub.class);
    private final RealtimeInterceptor interceptor = new RealtimeInterceptor(hub);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void conLaApiEnUnaSubrutaMarcarLeidaAvisaALasOtrasPestanas() {
        UserPrincipal principal = UserPrincipal.fromToken(userId, "ana@example.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        // En producción la API vive en /taskflow: la ruta que llega lleva esa subruta delante
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/taskflow/api/v1/notifications/read-all");
        request.setContextPath("/taskflow");
        request.addHeader(RealtimeInterceptor.CLIENT_HEADER, "pestana-1");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(hub).notifications(userId, "pestana-1");
    }

    @Test
    void conLaApiEnUnaSubrutaUnCambioEnElWorkspaceTambienAvisa() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/taskflow/api/v1/workspaces/equipo");
        request.setContextPath("/taskflow");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("slug", "equipo"));

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(hub).workspace("equipo", null);
        verify(hub, never()).notifications(any(), anyString());
    }
}
