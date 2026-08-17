package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Painel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PainelRepository extends JpaRepository<Painel, Integer> {
    List<Painel> findByAgenciaId(String agenciaId);
    Optional<Painel> findByAgenciaIdAndNumeroPainel(String agenciaId, Integer numeroPainel);
}
