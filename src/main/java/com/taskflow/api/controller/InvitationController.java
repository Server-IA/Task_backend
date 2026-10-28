package com.taskflow.api.controller;

import com.taskflow.api.dto.request.InviteMemberRequest;
import com.taskflow.api.dto.response.InvitationDetailsResponse;
import com.taskflow.api.dto.response.InvitationResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Invitaciones", description = "Invitar personas a un workspace por correo")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @Operation(summary = "Invitar por correo (project manager)", description = "La persona entra como MEMBER.")
    @PostMapping("/workspaces/{slug}/invitations")
    @PreAuthorize("@workspaceSecurity.isManager(#slug)")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
                                     @Valid @RequestBody InviteMemberRequest request) {
        return invitationService.invite(slug, me.getId(), request);
    }

    @Operation(summary = "Invitaciones pendientes del workspace (project manager)")
    @GetMapping("/workspaces/{slug}/invitations")
    @PreAuthorize("@workspaceSecurity.isManager(#slug)")
    public List<InvitationResponse> getPending(@PathVariable String slug) {
        return invitationService.getPending(slug);
    }

    @Operation(summary = "Cancelar una invitación pendiente (project manager)")
    @DeleteMapping("/workspaces/{slug}/invitations/{invitationId}")
    @PreAuthorize("@workspaceSecurity.isManager(#slug)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String slug, @PathVariable UUID invitationId) {
        invitationService.revoke(slug, invitationId);
    }

    @Operation(summary = "Ver una invitación con el token del correo", description = "Público: no requiere sesión.")
    @SecurityRequirements
    @GetMapping("/invitations/{token}")
    public InvitationDetailsResponse getDetails(@PathVariable String token) {
        return invitationService.getDetails(token);
    }

    @Operation(summary = "Aceptar invitación", description = "El correo del usuario debe coincidir con el invitado.")
    @PostMapping("/invitations/{token}/accept")
    public WorkspaceResponse accept(@PathVariable String token, @AuthenticationPrincipal UserPrincipal me) {
        return invitationService.accept(token, me.getId());
    }
}
