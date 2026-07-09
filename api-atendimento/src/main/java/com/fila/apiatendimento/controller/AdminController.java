package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.AgenciaRequest;
import com.fila.apiatendimento.dto.PainelRequest;
import com.fila.apiatendimento.dto.SalaRequest;
import com.fila.apiatendimento.entity.*;
import com.fila.apiatendimento.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AgenciaRepository agenciaRepository;
    private final PainelRepository painelRepository;
    private final SalaRepository salaRepository;

    public AdminController(AgenciaRepository agenciaRepository,
                           PainelRepository painelRepository,
                           SalaRepository salaRepository) {
        this.agenciaRepository = agenciaRepository;
        this.painelRepository = painelRepository;
        this.salaRepository = salaRepository;
    }

    @PostMapping("/agencia")
    public ResponseEntity<Agencia> criarAgencia(@RequestBody AgenciaRequest req) {
        Agencia a = new Agencia();
        a.setId(req.id());
        a.setNome(req.nome());
        return ResponseEntity.ok(agenciaRepository.save(a));
    }

    @GetMapping("/agencia")
    public List<Agencia> listarAgencias() {
        return agenciaRepository.findAll();
    }

    @PostMapping("/painel")
    public ResponseEntity<Painel> criarPainel(@RequestBody PainelRequest req) {
        Painel p = new Painel();
        p.setAgenciaId(req.agenciaId());
        p.setNumero(req.numero());
        p.setLocalizacao(req.localizacao());
        return ResponseEntity.ok(painelRepository.save(p));
    }

    @GetMapping("/painel/{agenciaId}")
    public List<Painel> listarPaineis(@PathVariable String agenciaId) {
        return painelRepository.findByAgenciaId(agenciaId);
    }

    @PostMapping("/sala")
    public ResponseEntity<Sala> criarSala(@RequestBody SalaRequest req) {
        List<Painel> paineis = painelRepository.findAllById(req.painelIds());
        if (paineis.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Sala s = new Sala();
        s.setAgenciaId(req.agenciaId());
        s.setNome(req.nome());
        s.setLocalizacao(req.localizacao());
        s.setPaineis(paineis);
        return ResponseEntity.ok(salaRepository.save(s));
    }

    @GetMapping("/sala/{agenciaId}")
    public List<Sala> listarSalas(@PathVariable String agenciaId) {
        return salaRepository.findByAgenciaId(agenciaId);
    }
}
