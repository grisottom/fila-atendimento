package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.TriagemRequest;
import com.fila.apiatendimento.dto.TriagemResponse;
import com.fila.apiatendimento.service.TriagemService;
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
}
