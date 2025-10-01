package com.kanstad.task.sistema.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.sistema.Sistema;
@Repository
public interface SistemaRepository extends JpaRepository<Sistema, Long> {

}