package com.taskflow.api.repository;

import com.taskflow.api.entity.RetroNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetroNoteRepository extends JpaRepository<RetroNote, UUID> {

    /**
     * Notas de la retrospectiva del ciclo, de la más antigua a la más reciente, con el autor, el responsable y la
     * tarea cargados.
     */
    @Query("""
            select n from RetroNote n
            join fetch n.author
            left join fetch n.owner
            left join fetch n.issue
            where n.cycle.id = :cycleId
            order by n.createdAt asc, n.id asc
            """)
    List<RetroNote> findAllByCycleId(@Param("cycleId") UUID cycleId);

    Optional<RetroNote> findByIdAndCycleId(UUID id, UUID cycleId);

    /** Votos que el usuario ya usó en la retrospectiva del ciclo. */
    @Query("select count(n) from RetroNote n join n.voters v where n.cycle.id = :cycleId and v.id = :userId")
    long countVotesByUser(@Param("cycleId") UUID cycleId, @Param("userId") UUID userId);
}
