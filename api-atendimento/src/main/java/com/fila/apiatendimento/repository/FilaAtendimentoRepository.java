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

    List<FilaAtendimento> findByPublicadoNoBrokerFalseAndStatus(String status);

    Optional<FilaAtendimento> findByTriagemUuid(java.util.UUID triagemUuid);

    /**
     * Agendados RETIDOS pela pré-triagem (status AGENDADO_FUTURO), ordenados por
     * horário agendado ASC. O OutboxPublisher percorre nessa ordem e publica os
     * elegíveis, para que o lote liberado no mesmo ciclo entre na fila JMS na
     * ordem de horário (a fila é FIFO entre mensagens de mesma prioridade).
     */
    List<FilaAtendimento> findByStatusOrderByHorarioAgendadoAsc(String status);

    /**
     * Conta itens "em curso" (CHAMANDO/EM_ATENDIMENTO) de uma agência. Usado pelo
     * gatilho "agência parada" da pré-triagem: zero → ninguém sendo atendido, pode
     * liberar os retidos sem furar a fila de ninguém.
     */
    long countByAgenciaIdAndStatusIn(String agenciaId, List<String> statuses);
}
