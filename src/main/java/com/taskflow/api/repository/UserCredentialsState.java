package com.taskflow.api.repository;

import java.time.Instant;

/**
 * Lo que decide si los access tokens de un usuario siguen valiendo: la cuenta activa y el último cambio de contraseña.
 */
public record UserCredentialsState(boolean active, Instant credentialsChangedAt) {
}
