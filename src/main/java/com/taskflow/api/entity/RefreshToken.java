package com.taskflow.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token emitido en el login. Solo se guarda su hash SHA-256.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    /** Cuándo se revocó. {@code null} si sigue activo (o se revocó antes de guardar esta fecha). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** El token que lo reemplazó al rotarlo. {@code null} si sigue activo o se revocó por otra causa. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    public boolean isUsable(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }

    /**
     * Se revocó al rotarlo (tiene sucesor) hace menos de {@code grace}: volver a usarlo puede ser una recarga de la
     * página que no alcanzó a guardar el token nuevo, no un robo.
     */
    public boolean rotatedWithin(Duration grace, Instant now) {
        return revoked && replacedBy != null && revokedAt != null && now.isBefore(revokedAt.plus(grace));
    }
}
