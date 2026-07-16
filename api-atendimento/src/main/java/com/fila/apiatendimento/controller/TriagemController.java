package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.AgendamentoResponse;
import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.dto.TriagemRequest;
import com.fila.apiatendimento.dto.TriagemResponse;
import com.fila.apiatendimento.service.TriagemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @PutMapping("/atualizar/{id}")
    public ResponseEntity<TriagemResponse> atualizar(@PathVariable Integer id, @RequestBody java.util.Map<String, String> body) {
        return ResponseEntity.ok(triagemService.atualizarServico(id, body.get("servicoId")));
    }

    @GetMapping("/agendamentos/{agenciaId}")
    public ResponseEntity<Map<String, Object>> agendamentosDoDia(
            @PathVariable String agenciaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        var p = triagemService.listarAgendamentosDoDia(agenciaId, page, size);
        return ResponseEntity.ok(Map.of(
                "content", p.getContent(),
                "totalPages", p.getTotalPages(),
                "totalElements", p.getTotalElements()
        ));
    }

    @GetMapping("/atendimentos/{agenciaId}")
    public ResponseEntity<List<AtendimentoResponse>> atendimentosDoDiaEsperando(@PathVariable String agenciaId) {
        return ResponseEntity.ok(triagemService.listarAtendimentosDoDiaEsperando(agenciaId));
    }
}
