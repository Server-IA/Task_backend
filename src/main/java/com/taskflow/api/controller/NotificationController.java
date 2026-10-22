package com.taskflow.api.controller;

import com.taskflow.api.dto.response.CountResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Notificaciones", description = "Notificaciones in-app del usuario autenticado")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "Mis notificaciones (las más recientes primero)")
    @GetMapping
    public PageResponse<NotificationResponse> getMyNotifications(
            @AuthenticationPrincipal UserPrincipal me,
            @RequestParam(defaultValue = "false") boolean unread,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return notificationService.getMyNotifications(me.getId(), unread, pageable);
    }

    @Operation(summary = "Cantidad de notificaciones sin leer")
    @GetMapping("/unread-count")
    public CountResponse countUnread(@AuthenticationPrincipal UserPrincipal me) {
        return notificationService.countUnread(me.getId());
    }

    @Operation(summary = "Marcar como leída")
    @PatchMapping("/{id}/read")
    public NotificationResponse markAsRead(@AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id) {
        return notificationService.markAsRead(me.getId(), id);
    }

    @Operation(summary = "Marcar todas como leídas")
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllAsRead(@AuthenticationPrincipal UserPrincipal me) {
        notificationService.markAllAsRead(me.getId());
    }
}
