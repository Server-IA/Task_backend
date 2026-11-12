package com.taskflow.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Si la API está viva y llega a la base de datos. Lo consultan Docker (HEALTHCHECK) y Dokploy para saber si el
 * contenedor está listo; no lleva sesión ni dice nada más que «ok».
 */
@Tag(name = "Salud", description = "Comprobación de que la API responde y llega a la base de datos")
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;

    @Operation(summary = "Estado de la API", description = "200 si responde y llega a la base de datos; 503 si no.")
    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "down"));
        }
    }
}
