package com.taskmanager.repository;

import com.taskmanager.model.Proyecto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProyectoRepository extends JpaRepository<Proyecto, Long> {
    List<Proyecto> findByEmpresaId(Long empresaId);

    long countByEstadoId(Long estadoId);

    long countByTipoProyectoId(Long tipoProyectoId);

    @Query("SELECT DISTINCT p FROM Proyecto p WHERE p.creador.id = :usuarioId OR EXISTS (" +
            "SELECT 1 FROM MiembroProyecto mp WHERE mp.proyecto = p AND mp.usuario.id = :usuarioId)")
    List<Proyecto> findAccessibleByUsuarioId(@Param("usuarioId") Long usuarioId);

    @Modifying
    @Query(value = "UPDATE proyectos SET deleted_at = NOW() WHERE empresa_id = :empresaId AND deleted_at IS NULL", nativeQuery = true)
    void softDeleteByEmpresaId(@Param("empresaId") Long empresaId);

    @Query(value = "SELECT id FROM proyectos WHERE empresa_id = :empresaId AND deleted_at IS NULL", nativeQuery = true)
    List<Long> findIdsByEmpresaId(@Param("empresaId") Long empresaId);
}
