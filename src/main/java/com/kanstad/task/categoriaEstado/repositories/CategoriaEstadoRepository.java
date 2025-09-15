package com.kanstad.task.categoriaEstado.repositories;

import com.kanstad.task.categoriaEstado.CategoriaEstado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoriaEstadoRepository extends JpaRepository<CategoriaEstado, Long> {

}
