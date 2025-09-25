package com.kanstad.task.tipoProyecto.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.tipoProyecto.TipoProyecto;

@Repository
public interface TipoProyectoRepository extends JpaRepository<TipoProyecto, Long> {

}
