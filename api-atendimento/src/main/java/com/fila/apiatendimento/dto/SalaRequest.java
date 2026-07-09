package com.fila.apiatendimento.dto;

import java.util.List;

public record SalaRequest(String agenciaId, String nome, String localizacao, List<Integer> painelIds) {}
