package com.taskmanager.repository;

import com.taskmanager.model.TareaAsignado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TareaAsignadoRepository extends JpaRepository<TareaAsignado, Long> {
    boolean existsByTareaIdAndUsuarioId(Long tareaId, Long usuarioId);
    void deleteByTareaId(Long tareaId);
}
