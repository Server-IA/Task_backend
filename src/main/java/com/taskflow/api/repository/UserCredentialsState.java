package com.taskflow.api.repository;

/**
 * Lo que decide si los access tokens de un usuario siguen valiendo: la cuenta activa y la versión de sus credenciales
 * (sube con cada cambio de contraseña).
 */
public record UserCredentialsState(boolean active, int credentialsVersion) {
}
