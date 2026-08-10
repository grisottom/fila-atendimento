package com.fila.apiatendimento.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        String mensagem = ex.getMessage() != null ? ex.getMessage() : "Erro interno inesperado";
        return ResponseEntity.badRequest().body(Map.of("erro", mensagem));
    }
}
