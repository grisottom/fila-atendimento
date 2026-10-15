package com.fila.apiatendimento.dto;

import java.time.LocalDateTime;

public record AtendimentoResponse(Integer id, String senha, Long cpf, String nomePessoa, String servicoId,
                                  String status, String estacao, String aviso, LocalDateTime horarioChegada) {

    // Construtor de conveniência (sem aviso e sem horarioChegada) — mantém as chamadas existentes.
    public AtendimentoResponse(Integer id, String senha, Long cpf, String nomePessoa, String servicoId, String status, String estacao) {
        this(id, senha, cpf, nomePessoa, servicoId, status, estacao, null, null);
    }

    // Construtor de conveniência (com aviso, sem horarioChegada) — mantém as chamadas existentes.
    public AtendimentoResponse(Integer id, String senha, Long cpf, String nomePessoa, String servicoId, String status, String estacao, String aviso) {
        this(id, senha, cpf, nomePessoa, servicoId, status, estacao, aviso, null);
    }
}
