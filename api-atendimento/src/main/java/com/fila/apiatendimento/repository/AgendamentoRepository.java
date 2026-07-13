package com.fila.apiatendimento.repository;

import com.fila.apiatendimento.entity.Agendamento;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AgendamentoRepository extends JpaRepository<Agendamento, Integer> {
    List<Agendamento> findByCpfAndAgenciaIdAndDataHoraBetween(
        Long cpf, String agenciaId, LocalDateTime inicio, LocalDateTime fim);

    List<Agendamento> findByAgenciaIdAndDataHoraBetweenOrderByDataHoraAsc(
        String agenciaId, LocalDateTime inicio, LocalDateTime fim);
}
