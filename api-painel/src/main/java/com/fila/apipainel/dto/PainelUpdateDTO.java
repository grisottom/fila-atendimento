package com.fila.apipainel.dto;

public record PainelUpdateDTO(
    String agenciaId,
    Integer painelId,
    String senha,
    String nomePessoa,
    String sala,
    String status // CHAMANDO, EM_ATENDIMENTO, AUSENTE
) {}
