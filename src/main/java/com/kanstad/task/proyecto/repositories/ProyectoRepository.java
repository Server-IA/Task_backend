package com.kanstad.task.proyecto.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.proyecto.Proyecto;

@Repository
public interface ProyectoRepository extends JpaRepository<Proyecto, Long> {

}
