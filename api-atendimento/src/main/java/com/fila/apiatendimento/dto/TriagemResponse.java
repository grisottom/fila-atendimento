package com.fila.apiatendimento.dto;

import java.time.LocalDateTime;

public record TriagemResponse(String senha, String nomePessoa, String servicoId, LocalDateTime horarioAgendado) {}
