package com.fila.apiatendimento.config;

/**
 * Exceção para condições de negócio esperadas (estado atual não permite a
 * operação), em contraste com falhas internas inesperadas.
 *
 * O {@link GlobalExceptionHandler} mapeia esta exceção para HTTP 409 (Conflict),
 * evitando que situações previsíveis (ex.: nenhum painel ativo para o serviço)
 * sejam reportadas como erro 500.
 */
public class NegocioException extends RuntimeException {

    public NegocioException(String mensagem) {
        super(mensagem);
    }
}
