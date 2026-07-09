package com.fila.apiatendimento.dto;

public record AtendimentoResponse(Integer id, String senha, String nomePessoa, String servicoId, String status, String sala) {}
