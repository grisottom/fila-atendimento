package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Painel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PainelRepository extends JpaRepository<Painel, Integer> {
    List<Painel> findByAgenciaId(String agenciaId);
}
