package com.fila.apipainel.dto;

public record PainelUpdateDTO(
    String agenciaId,
    Integer painelId,
    String senha,
    String nomePessoa,
    String estacao,
    String status // CHAMANDO, EM_ATENDIMENTO, AUSENTE, CANCELADO
) {}
