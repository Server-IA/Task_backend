package com.kanstad.task.proyectoFase.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.proyectoFase.ProyectoFase;

@Repository
public interface ProyectoFaseRepository extends JpaRepository<ProyectoFase, Long> {


}
