package com.taskflow.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Último cambio o restablecimiento de la contraseña; {@code null} si nunca cambió. */
    @Column(name = "credentials_changed_at")
    private Instant credentialsChangedAt;

    /**
     * Sube en cada cambio o restablecimiento de la contraseña. Los access tokens llevan la versión con la que se
     * emitieron (claim {@code cv}) y solo vale la actual ({@code CredentialsGuard}).
     */
    @Column(name = "credentials_version", nullable = false)
    private int credentialsVersion;
}
