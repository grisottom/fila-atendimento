package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.PainelServico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;

public interface PainelServicoRepository extends JpaRepository<PainelServico, Integer> {

    List<PainelServico> findByServicoId(String servicoId);

    List<PainelServico> findByServicoIdAndPainelAgenciaId(String servicoId, String agenciaId);

    List<PainelServico> findByPainelId(Integer painelId);

    @Modifying
    void deleteByPainelIdAndServicoId(Integer painelId, String servicoId);
}
