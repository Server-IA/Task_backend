package com.taskflow.api.controller;

import com.taskflow.api.github.GithubService;
import com.taskflow.api.github.GithubService.WorkspaceStatus;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.security.WorkspaceSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "GitHub", description = "Conectar el workspace con GitHub")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/github")
@RequiredArgsConstructor
public class GithubController {

    private final GithubService githubService;
    private final WorkspaceSecurity workspaceSecurity;

    /** A dónde va el navegador para instalar la app. */
    public record InstallLink(String url) {
    }

    @Operation(summary = "Cómo está la conexión", description = "Si el workspace está conectado, con qué cuenta y qué repositorios comparte.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public WorkspaceStatus status(@PathVariable String slug) {
        // Los repositorios que comparte la instalación, solo para quien configura GitHub
        return githubService.workspace(slug, workspaceSecurity.can(slug, "GITHUB_MANAGE"));
    }

    @Operation(summary = "Enlace para instalar la app en GitHub", description = "Vale una hora y solo para este workspace.")
    @PostMapping("/install")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'GITHUB_MANAGE')")
    public InstallLink install(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me) {
        return new InstallLink(githubService.installUrl(slug, me.getId()));
    }

    @Operation(summary = "Volver a leer los repositorios", description = "Trae de GitHub la lista de los que la app puede ver.")
    @PostMapping("/sync")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'GITHUB_MANAGE')")
    public WorkspaceStatus sync(@PathVariable String slug) {
        return githubService.syncRepositories(slug);
    }

    @Operation(summary = "Desconectar GitHub", description = "Quita la app de la cuenta de GitHub y borra lo que TaskFlow guardaba de ella.")
    @DeleteMapping
    @PreAuthorize("@workspaceSecurity.can(#slug, 'GITHUB_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String slug) {
        githubService.disconnect(slug);
    }
}
