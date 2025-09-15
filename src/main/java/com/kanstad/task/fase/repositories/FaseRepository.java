package com.kanstad.task.fase.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.fase.Fase;

@Repository
public interface FaseRepository extends JpaRepository<Fase, Long> {

}
