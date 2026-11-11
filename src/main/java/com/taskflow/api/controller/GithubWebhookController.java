package com.taskflow.api.controller;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.github.GithubKeys;
import com.taskflow.api.github.GithubProperties;
import com.taskflow.api.github.GithubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * Lo que GitHub llama sin sesión: los avisos de la app (firmados con su secreto) y la vuelta del navegador cuando alguien
 * termina de instalarla (con el enlace que firmó TaskFlow).
 */
@Slf4j
@Tag(name = "GitHub (avisos)", description = "Lo que llama GitHub: avisos de la app y vuelta de la instalación")
@RestController
@RequestMapping("/api/v1/github")
@RequiredArgsConstructor
public class GithubWebhookController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GithubService githubService;
    private final GithubProperties properties;
    private final AppProperties appProperties;

    @Operation(summary = "Aviso de GitHub", description = "Solo se atiende si viene firmado con el secreto de la app.")
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                        @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                        @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery) {
        if (!properties.configured()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (body == null || !GithubKeys.validSignature(properties.webhookSecret(), body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(body);
        } catch (JacksonException ex) {
            return ResponseEntity.badRequest().build();
        }
        githubService.handle(event, delivery, payload);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Vuelta de instalar la app", description = "Guarda la instalación para el workspace que la pidió y lleva a sus ajustes.")
    @GetMapping("/setup")
    public ResponseEntity<Void> setup(@RequestParam(value = "installation_id", required = false) Long installationId,
                                      @RequestParam(value = "state", required = false) String state) {
        String target;
        if (installationId == null || state == null || state.isBlank()) {
            // Cambios hechos desde GitHub (o una instalación que espera la aprobación de la organización): no hay nada que guardar
            target = appProperties.frontendUrl() + "/";
        } else {
            try {
                target = githubService.completeSetup(installationId, state);
            } catch (RuntimeException ex) {
                log.warn("GitHub: no se pudo terminar la instalación {}", installationId, ex);
                target = appProperties.frontendUrl() + "/?github=failed";
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(target).toString()).build();
    }
}
