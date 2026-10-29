package com.taskflow.api.security;

import java.time.Instant;

/**
 * Un access token ya validado (firma y vencimiento): su usuario y cuándo se emitió ({@code iat}, al segundo).
 */
public record AccessToken(UserPrincipal principal, Instant issuedAt) {
}
