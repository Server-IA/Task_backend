package com.taskflow.api.security;

/**
 * Un access token ya validado (firma y vencimiento): su usuario y la versión de las credenciales con la que se emitió
 * (claim {@code cv}).
 */
public record AccessToken(UserPrincipal principal, int credentialsVersion) {
}
