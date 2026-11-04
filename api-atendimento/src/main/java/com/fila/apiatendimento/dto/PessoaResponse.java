package com.fila.apiatendimento.dto;

import java.time.LocalDate;

/**
 * Dados da pessoa para a triagem. A `idade` é calculada no backend a partir da
 * `dataNascimento` e serve ao indicador read-only de prioridade por idade na tela
 * (a classificação em si continua no backend, ver PrioridadeCalculator).
 */
public record PessoaResponse(Long cpf, String nome, LocalDate dataNascimento, Integer idade) {}
