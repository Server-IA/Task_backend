package com.taskflow.api.repository;

import com.taskflow.api.entity.VerificationToken;
import com.taskflow.api.entity.enums.VerificationTokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    Optional<VerificationToken> findByTokenHashAndType(String tokenHash, VerificationTokenType type);

    /** Invalida los tokens pendientes del usuario para que solo sirva el último enviado. */
    @Modifying
    @Query("""
            update VerificationToken t set t.used = true
            where t.user.id = :userId and t.type = :type and t.used = false
            """)
    int invalidateAll(@Param("userId") UUID userId, @Param("type") VerificationTokenType type);

    /** Limpieza programada: los usados y los vencidos ya no sirven. */
    @Modifying
    @Query("delete from VerificationToken t where t.used = true or t.expiresAt < :now")
    int deleteUsedOrExpired(@Param("now") java.time.Instant now);
}
