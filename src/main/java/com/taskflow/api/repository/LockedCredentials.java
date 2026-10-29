package com.taskflow.api.repository;

/**
 * Credenciales de un usuario leídas con su fila bloqueada ({@link UserRepository#lockCredentials}).
 */
public interface LockedCredentials {

    String getPasswordHash();

    int getCredentialsVersion();
}
