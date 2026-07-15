package com.fila.apiatendimento.dto;

public record EstacaoRequest(String agenciaId, String tipoEstacao, Integer numeroEstacao, String localizacao, Integer painelId) {}
