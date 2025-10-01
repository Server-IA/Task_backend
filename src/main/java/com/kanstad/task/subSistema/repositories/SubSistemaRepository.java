package com.kanstad.task.subSistema.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.subSistema.SubSistema;

@Repository
public interface SubSistemaRepository extends JpaRepository<SubSistema, Long> {

}
