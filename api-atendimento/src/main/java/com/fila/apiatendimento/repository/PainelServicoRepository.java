package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.PainelServico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PainelServicoRepository extends JpaRepository<PainelServico, Integer> {

    List<PainelServico> findByServicoId(String servicoId);

    List<PainelServico> findByServicoIdAndPainelAgenciaId(String servicoId, String agenciaId);

    List<PainelServico> findByPainelId(Integer painelId);

    @Modifying
    void deleteByPainelIdAndServicoId(Integer painelId, String servicoId);

    @Query("""
        SELECT COUNT(ps) > 0 FROM PainelServico ps
        WHERE ps.servicoId = :servicoId
          AND ps.painel.agenciaId = :agenciaId
          AND ps.painel.ultimoHeartbeat IS NOT NULL
          AND ps.painel.ultimoHeartbeat > :limite
        """)
    boolean existePainelAtivoParaServico(@Param("servicoId") String servicoId,
                                         @Param("agenciaId") String agenciaId,
                                         @Param("limite") java.time.LocalDateTime limite);
}
