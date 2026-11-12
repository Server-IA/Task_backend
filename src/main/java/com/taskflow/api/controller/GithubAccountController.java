package com.taskflow.api.controller;

import com.taskflow.api.github.GithubAccounts;
import com.taskflow.api.github.GithubAccounts.MyAccount;
import com.taskflow.api.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cuenta de GitHub de quien tiene la sesión: enlazarla, ver cuál es y desenlazarla. Con ella sus acciones en GitHub
 * (crear una rama, ejecutar un workflow) salen a su nombre.
 */
@Tag(name = "Mi cuenta de GitHub", description = "Enlazar la cuenta de GitHub del usuario autenticado")
@RestController
@RequestMapping("/api/v1/users/me/github")
@RequiredArgsConstructor
public class GithubAccountController {

    private final GithubAccounts accounts;

    /** A dónde va el navegador para autorizar en GitHub. */
    public record LinkUrl(String url) {
    }

    /** Lo que GitHub trae de vuelta al terminar de autorizar. */
    public record LinkResult(String code, String state) {
    }

    @Operation(summary = "Mi cuenta de GitHub", description = "Si está enlazada y cuál es.")
    @GetMapping
    public MyAccount status(@AuthenticationPrincipal UserPrincipal me) {
        return accounts.status(me.getId());
    }

    @Operation(summary = "Enlace para conectar mi cuenta", description = "Vale quince minutos y solo para quien lo pide.")
    @PostMapping("/link")
    public LinkUrl link(@AuthenticationPrincipal UserPrincipal me) {
        return new LinkUrl(accounts.linkUrl(me.getId()));
    }

    @Operation(summary = "Terminar de conectar mi cuenta", description = "Con el código y el estado que GitHub trae de vuelta.")
    @PostMapping("/link/complete")
    public MyAccount complete(@AuthenticationPrincipal UserPrincipal me, @RequestBody LinkResult result) {
        return accounts.complete(me.getId(), result.code(), result.state());
    }

    @Operation(summary = "Desconectar mi cuenta de GitHub")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal UserPrincipal me) {
        accounts.unlink(me.getId());
    }
}
