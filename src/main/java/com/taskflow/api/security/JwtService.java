package com.taskflow.api.security;

import com.taskflow.api.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Emite y valida los access tokens (JWT firmados con HMAC-SHA).
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessExpiration;

    public JwtService(AppProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos " + MIN_SECRET_BYTES + " caracteres");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiration = Duration.ofMinutes(properties.jwt().accessExpirationMinutes());
    }

    /** Claim con la versión de las credenciales del usuario al emitir el token. */
    static final String CREDENTIALS_VERSION_CLAIM = "cv";

    /**
     * @param credentialsVersion la versión de las credenciales del usuario ({@code User.credentialsVersion}): el token
     *                           deja de valer cuando cambia la contraseña ({@link CredentialsGuard})
     */
    public String generateAccessToken(UUID userId, String email, int credentialsVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim(CREDENTIALS_VERSION_CLAIM, credentialsVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessExpiration)))
                .signWith(key)
                .compact();
    }

    public long getAccessExpirationSeconds() {
        return accessExpiration.toSeconds();
    }

    /**
     * Devuelve el usuario del token y la versión de sus credenciales, o vacío si la firma no es válida o ya expiró. Un
     * token sin {@code cv} (emitido antes de que existiera) cuenta como versión 0.
     */
    public Optional<AccessToken> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UserPrincipal principal = UserPrincipal.fromToken(
                    UUID.fromString(claims.getSubject()), claims.get("email", String.class));
            Integer version = claims.get(CREDENTIALS_VERSION_CLAIM, Integer.class);
            return Optional.of(new AccessToken(principal, version == null ? 0 : version));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
