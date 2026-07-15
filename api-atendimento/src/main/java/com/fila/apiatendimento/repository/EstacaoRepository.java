package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Estacao;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EstacaoRepository extends JpaRepository<Estacao, Integer> {
    @EntityGraph(attributePaths = "painel")
    List<Estacao> findByAgenciaId(String agenciaId);

    @EntityGraph(attributePaths = "painel")
    Optional<Estacao> findById(Integer id);
}
