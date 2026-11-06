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

    /**
     * El estado, con {@code FOR KEY SHARE} hasta el final de la transacción (el mismo bloqueo que toma la clave foránea
     * al guardar la tarea, pero antes de mirar nada): quien pone una tarea en él (crearla, moverla) no se cruza con quien
     * lo elimina. Si el borrado llegó primero, espera y ya no lo encuentra; si no, el borrado espera y ve la tarea. Antes
     * la tarea fallaba con un 409 genérico por la clave foránea. No choca con renombrarlo, reordenarlo ni cambiarle el
     * grupo (esos no tocan la clave).
     */
    @Query(value = "SELECT * FROM states WHERE id = :id AND project_id = :projectId FOR KEY SHARE", nativeQuery = true)
    Optional<State> findForShare(@Param("id") UUID id, @Param("projectId") UUID projectId);

    /** El estado, con {@code FOR UPDATE} hasta el final de la transacción (para eliminarlo; ver {@link #findForShare}). */
    @Query(value = "SELECT * FROM states WHERE id = :id AND project_id = :projectId FOR UPDATE", nativeQuery = true)
    Optional<State> findForUpdate(@Param("id") UUID id, @Param("projectId") UUID projectId);

    /** Estado por defecto para los issues nuevos: el primero del tablero. */
    Optional<State> findFirstByProjectIdOrderByPositionAsc(UUID projectId);

    /** Primer estado del tablero dentro del grupo indicado (por ejemplo, el primero «sin empezar»). */
    Optional<State> findFirstByProjectIdAndStateGroupOrderByPositionAsc(UUID projectId, StateGroup stateGroup);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);

    long countByProjectId(UUID projectId);

    @Query("select coalesce(max(s.position), -1) from State s where s.project.id = :projectId")
    int findMaxPosition(@Param("projectId") UUID projectId);
}
