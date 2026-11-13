package com.taskflow.api.dto.response;

/**
 * Respuesta del registro: siempre la misma, exista o no una cuenta con ese correo (no revela quién está registrado).
 * Lleva el correo para que la pantalla diga a dónde se mandó la verificación.
 */
public record RegisterResponse(String email) {
}
