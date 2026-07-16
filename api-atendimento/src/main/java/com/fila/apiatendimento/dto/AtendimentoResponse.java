package com.fila.apiatendimento.dto;

public record AtendimentoResponse(Integer id, String senha, Long cpf, String nomePessoa, String servicoId, String status, String estacao) {}
