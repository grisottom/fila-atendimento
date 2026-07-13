package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.FilaAtendimento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface FilaAtendimentoRepository extends JpaRepository<FilaAtendimento, Integer> {

    @Query(value = """
        SELECT f.* FROM fila_atendimento f
        INNER JOIN servico s ON f.servico_id = s.id
        WHERE f.agencia_id = :agenciaId
          AND f.status = 'AGUARDANDO'
          AND s.permissao_exigida IN (:permissoes)
        ORDER BY f.horario_agendado NULLS LAST, f.posicao_fila
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<FilaAtendimento> findProximoParaAtendimento(
        @Param("agenciaId") String agenciaId,
        @Param("permissoes") List<String> permissoes);

    List<FilaAtendimento> findByAgenciaIdAndStatusIn(String agenciaId, List<String> statuses);

    Optional<FilaAtendimento> findFirstByAtendenteUsernameAndStatusInOrderByHorarioChamadaDesc(String username, List<String> statuses);

    @Query("SELECT COALESCE(MAX(f.posicaoFila), 0) FROM FilaAtendimento f WHERE f.agenciaId = :agenciaId")
    Integer findMaxPosicaoFila(@Param("agenciaId") String agenciaId);

    @Query(value = """
        SELECT f.* FROM fila_atendimento f
        INNER JOIN servico s ON f.servico_id = s.id
        WHERE f.agencia_id = :agenciaId
          AND f.status = 'AGUARDANDO'
          AND s.permissao_exigida IN (:permissoes)
        ORDER BY f.horario_agendado NULLS LAST, f.posicao_fila
        """, nativeQuery = true)
    List<FilaAtendimento> findFilaDisponivel(
        @Param("agenciaId") String agenciaId,
        @Param("permissoes") List<String> permissoes);
}
