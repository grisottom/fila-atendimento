package com.fila.apipainel.controller;

import com.fila.apipainel.service.PainelSseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/painel")
public class PainelController {

    private final PainelSseService painelSseService;

    public PainelController(PainelSseService painelSseService) {
        this.painelSseService = painelSseService;
    }

    @GetMapping(value = "/sse/{agenciaId}/{painelId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter conectar(@PathVariable String agenciaId, @PathVariable Integer painelId,
                               @AuthenticationPrincipal Jwt jwt) {
        String agenciaDoUsuario = jwt.getClaimAsString("agencia");
        if (agenciaDoUsuario == null || !agenciaDoUsuario.equals(agenciaId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Usuário não pertence à agência " + agenciaId);
        }
        return painelSseService.registrar(agenciaId, painelId);
    }

}

