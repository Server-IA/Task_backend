package com.kanstad.task.empresa.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kanstad.task.empresa.Empresa;
import java.util.Optional;
@Repository
public interface EmpresaRepository extends JpaRepository<Empresa, Long> {
	Optional<Empresa> findByCorreo(String correo);
}
