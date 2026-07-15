package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.AgendamentoResponse;
import com.fila.apiatendimento.dto.TriagemRequest;
import com.fila.apiatendimento.dto.TriagemResponse;
import com.fila.apiatendimento.service.TriagemService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/triagem")
public class TriagemController {

    private final TriagemService triagemService;

    public TriagemController(TriagemService triagemService) {
        this.triagemService = triagemService;
    }

    @PostMapping("/recepcionar")
    public ResponseEntity<TriagemResponse> recepcionar(@RequestBody TriagemRequest request) {
        return ResponseEntity.ok(triagemService.recepcionar(request));
    }

    @GetMapping("/agendamentos/{agenciaId}")
    public ResponseEntity<Page<AgendamentoResponse>> agendamentosDoDia(
            @PathVariable String agenciaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        return ResponseEntity.ok(triagemService.listarAgendamentosDoDia(agenciaId, page, size));
    }
}
