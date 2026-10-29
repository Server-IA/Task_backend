package com.taskflow.api.repository;

import com.taskflow.api.entity.State;
import com.taskflow.api.entity.enums.StateGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StateRepository extends JpaRepository<State, UUID> {

    List<State> findAllByProjectIdOrderByPositionAsc(UUID projectId);

    /** Los estados del proyecto, con sus filas bloqueadas hasta el final de la transacción (para reordenarlos). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from State s where s.project.id = :projectId order by s.position, s.id")
    List<State> findAllByProjectIdForUpdate(@Param("projectId") UUID projectId);

    Optional<State> findByIdAndProjectId(UUID id, UUID projectId);

    /** Estado por defecto para los issues nuevos: el primero del tablero. */
    Optional<State> findFirstByProjectIdOrderByPositionAsc(UUID projectId);

    /** Primer estado del tablero dentro del grupo indicado (por ejemplo, el primero «sin empezar»). */
    Optional<State> findFirstByProjectIdAndStateGroupOrderByPositionAsc(UUID projectId, StateGroup stateGroup);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);

    long countByProjectId(UUID projectId);

    @Query("select coalesce(max(s.position), -1) from State s where s.project.id = :projectId")
    int findMaxPosition(@Param("projectId") UUID projectId);
}
