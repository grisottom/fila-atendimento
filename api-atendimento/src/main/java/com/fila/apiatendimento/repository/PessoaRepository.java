package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Pessoa;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PessoaRepository extends JpaRepository<Pessoa, Long> {
}
