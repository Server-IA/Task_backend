package com.kanstad.task.rama.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.rama.Rama;

@Repository
public interface RamaRepository extends JpaRepository<Rama, Long> {

}
