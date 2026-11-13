package com.taskflow.api.repository;

import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.entity.enums.StateGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectModuleRepository extends JpaRepository<ProjectModule, UUID> {

    List<ProjectModule> findAllByProjectIdOrderByNameAsc(UUID projectId);

    Optional<ProjectModule> findByIdAndProjectId(UUID id, UUID projectId);

    /**
     * Avance de los módulos indicados; un módulo sin issues no aparece en el resultado. Cuenta el trabajo
     * (tareas y bugs): las historias no, y las canceladas no suman al total.
     */
    @Query("""
            select m.id as id, coalesce(sum(case when i.state.stateGroup <> com.taskflow.api.entity.enums.StateGroup.CANCELLED
                       then 1 else 0 end), 0) as total,
                   coalesce(sum(case when i.state.stateGroup = :completed then 1 else 0 end), 0) as completed
            from ProjectModule m join m.issues i
            where m.id in :ids and i.type <> com.taskflow.api.entity.enums.IssueType.STORY
            group by m.id
            """)
    List<IssueProgress> findProgress(@Param("ids") Collection<UUID> ids,
                                     @Param("completed") StateGroup completed);

    /** El módulo con su fila bloqueada hasta el final de la transacción (agregar tareas a la vez no choca). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from ProjectModule m where m.id = :id and m.project.id = :projectId")
    java.util.Optional<ProjectModule> findForUpdate(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
                                                    @org.springframework.data.repository.query.Param("projectId") java.util.UUID projectId);
}
