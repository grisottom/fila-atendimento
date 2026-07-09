package com.fila.apipainel.controller;

import com.fila.apipainel.dto.PainelUpdateDTO;
import com.fila.apipainel.service.PainelSseService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/painel")
public class PainelController {

    private final PainelSseService painelSseService;

    public PainelController(PainelSseService painelSseService) {
        this.painelSseService = painelSseService;
    }

    @GetMapping(value = "/sse/{agenciaId}/{painelId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter conectar(@PathVariable String agenciaId, @PathVariable Integer painelId) {
        return painelSseService.registrar(agenciaId, painelId);
    }

}

