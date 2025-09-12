package com.kanstad.task.estado.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.estado.Estado;

@Repository
public interface EstadoRepository extends JpaRepository<Estado, Long> {

}
