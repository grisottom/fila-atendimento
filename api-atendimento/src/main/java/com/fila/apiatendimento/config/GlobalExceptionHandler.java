package com.fila.apiatendimento.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Erros de negócio (lançados intencionalmente nos services)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBusiness(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

    // Erros internos (falhas inesperadas)
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        String mensagem = ex.getMessage() != null ? ex.getMessage() : "Erro interno inesperado";

        // Erros de negócio conhecidos → 400
        if (mensagem.contains("Nenhum atendimento na fila") ||
            mensagem.contains("Estação não encontrada") ||
            mensagem.contains("Estação sem painel") ||
            mensagem.contains("Atendimento não encontrado")) {
            return ResponseEntity.badRequest().body(Map.of("erro", mensagem));
        }

        // Erros internos → 500
        log.error("Erro interno: {}", mensagem, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("erro", mensagem));
    }
}
