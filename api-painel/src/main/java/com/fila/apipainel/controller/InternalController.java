package com.fila.apipainel.controller;

import com.fila.apipainel.dto.PainelUpdateDTO;
import com.fila.apipainel.service.PainelSseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private final PainelSseService painelSseService;
    private final String apiKey;

    public InternalController(PainelSseService painelSseService,
                              @Value("${app.internal-api-key}") String apiKey) {
        this.painelSseService = painelSseService;
        this.apiKey = apiKey;
    }

    @PostMapping("/publicar")
    public ResponseEntity<Void> publicar(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody PainelUpdateDTO update) throws Exception {
        if (!apiKey.equals(key)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        painelSseService.publicar(update);
        return ResponseEntity.ok().build();
    }
}
