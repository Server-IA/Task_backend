package com.taskflow.api.repository;

import com.taskflow.api.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code revoked = true} marca un token ya rotado ({@code replaced_by} apunta a su sucesor) o revocado al detectar una
 * reutilización ({@code replaced_by} vacío). Si alguien vuelve a usar uno rotado, se asume que fue robado, salvo dentro
 * del margen de {@code app.tokens.refresh-reuse-grace-seconds} (ver {@code AuthServiceImpl#refresh}).
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
    @Query("update RefreshToken t set t.revoked = true, t.revokedAt = :now where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);

    /** Anota el sucesor de los tokens recién rotados: con él, reutilizarlos dentro del margen no cierra la sesión. */
    @Modifying
    @Query("update RefreshToken t set t.replacedBy = :replacement where t.id in :ids")
    int linkReplacement(@Param("ids") Collection<UUID> ids, @Param("replacement") UUID replacement);

    /** Limpieza programada: los vencidos (los rotados se guardan hasta vencer, para detectar su reutilización). */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    /**
     * Logout: borra la sesión entera del token {@code tokenId}: sus sucesores (los tokens que lo reemplazaron al rotar,
     * hasta el vivo) y, desde todos ellos, hacia atrás, los que fueron reemplazados por alguno (los rotados antes, a
     * cualquier distancia). Solo tokens de {@code userId}. Antes se borraban los sucesores y un solo paso hacia atrás: un
     * token de dos rotaciones atrás (otra pestaña que se quedó con él) quedaba apuntando a un sucesor borrado, y reusarlo
     * dentro del margen contaba como robo y cerraba también las sesiones de los otros equipos.
     * <p>
     * {@code UNION} (no {@code UNION ALL}) descarta las filas repetidas: el recorrido termina aunque los datos tuvieran
     * un ciclo.
     */
    @Modifying
    @Query(value = """
            WITH RECURSIVE forward (id, replaced_by) AS (
                SELECT id, replaced_by FROM refresh_tokens WHERE id = :tokenId AND user_id = :userId
                UNION
                SELECT t.id, t.replaced_by FROM refresh_tokens t JOIN forward f ON t.id = f.replaced_by
                WHERE t.user_id = :userId
            ), session (id) AS (
                SELECT id FROM forward
                UNION
                SELECT t.id FROM refresh_tokens t JOIN session s ON t.replaced_by = s.id
                WHERE t.user_id = :userId
            )
            DELETE FROM refresh_tokens WHERE id IN (SELECT id FROM session)
            """, nativeQuery = true)
    int deleteSession(@Param("userId") UUID userId, @Param("tokenId") UUID tokenId);
}
