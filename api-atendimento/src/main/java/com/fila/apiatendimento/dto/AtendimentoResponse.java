package com.fila.apiatendimento.dto;

public record AtendimentoResponse(Integer id, String senha, Long cpf, String nomePessoa, String servicoId, String status, String estacao, String aviso) {
    public AtendimentoResponse(Integer id, String senha, Long cpf, String nomePessoa, String servicoId, String status, String estacao) {
        this(id, senha, cpf, nomePessoa, servicoId, status, estacao, null);
    }
}
