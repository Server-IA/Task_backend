package com.taskflow.api.repository;

import com.taskflow.api.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code revoked = true} marca un token ya rotado. Si alguien lo vuelve a usar, se asume que fue robado.
 * Los tokens cerrados por logout o cambio de contraseña se eliminan.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** El usuario del token, sin cargar el token (se lee después de bloquear la fila del usuario). */
    @Query("select t.user.id from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.user.id = :userId and t.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    /**
     * Rota el token de forma atómica: lo marca como usado solo si nadie lo usó antes. Devuelve 0 si otra petición se
     * adelantó (el mismo token usado dos veces a la vez cuenta como reutilizado). La fila queda bloqueada hasta el
     * final de la transacción, así la segunda petición espera y ve el cambio de la primera.
     */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") UUID id);

    /** Limpieza programada: los vencidos (los rotados se guardan hasta vencer, para detectar su reutilización). */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") java.time.Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from RefreshToken t where t.tokenHash = :tokenHash")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);
}
