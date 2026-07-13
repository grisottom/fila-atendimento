package com.fila.apiatendimento.dto;

import java.time.LocalDateTime;

public record AgendamentoResponse(Long cpf, String nomePessoa, String servicoId, LocalDateTime dataHora) {}
