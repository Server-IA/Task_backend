package com.taskmanager.repository;

import com.taskmanager.model.Tarea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TareaRepository extends JpaRepository<Tarea, Long> {
    List<Tarea> findByProyectoId(Long proyectoId);

    @Query("SELECT DISTINCT t FROM Tarea t JOIN t.asignados a WHERE a.usuario.id = :asignadoId")
    List<Tarea> findByAsignadoId(@Param("asignadoId") Long asignadoId);

    List<Tarea> findByProyectoIdAndEstadoId(Long proyectoId, Long estadoId);
    List<Tarea> findByProyectoIdOrderByOrdenAsc(Long proyectoId);

    long countByEstadoId(Long estadoId);

    @Query("SELECT t FROM Tarea t WHERE t.proyecto.id IN :proyectoIds ORDER BY t.proyecto.id ASC, t.orden ASC")
    List<Tarea> findByProyectoIdIn(@Param("proyectoIds") List<Long> proyectoIds);

    @Modifying
    @Query(value = "UPDATE tareas SET deleted_at = NOW() WHERE proyecto_id = :proyectoId AND deleted_at IS NULL", nativeQuery = true)
    void softDeleteByProyectoId(@Param("proyectoId") Long proyectoId);

    @Query(value = "SELECT id FROM tareas WHERE proyecto_id = :proyectoId AND deleted_at IS NULL", nativeQuery = true)
    List<Long> findIdsByProyectoId(@Param("proyectoId") Long proyectoId);
}
