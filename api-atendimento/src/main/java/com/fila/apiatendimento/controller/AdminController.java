package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.AgenciaRequest;
import com.fila.apiatendimento.dto.EstacaoRequest;
import com.fila.apiatendimento.dto.PainelRequest;
import com.fila.apiatendimento.entity.*;
import com.fila.apiatendimento.repository.*;
import com.fila.apiatendimento.service.KeycloakAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AgenciaRepository agenciaRepository;
    private final PainelRepository painelRepository;
    private final EstacaoRepository estacaoRepository;
    private final ServicoRepository servicoRepository;
    private final KeycloakAdminService keycloakAdminService;

    public AdminController(AgenciaRepository agenciaRepository,
                           PainelRepository painelRepository,
                           EstacaoRepository estacaoRepository,
                           ServicoRepository servicoRepository,
                           KeycloakAdminService keycloakAdminService) {
        this.agenciaRepository = agenciaRepository;
        this.painelRepository = painelRepository;
        this.estacaoRepository = estacaoRepository;
        this.servicoRepository = servicoRepository;
        this.keycloakAdminService = keycloakAdminService;
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
        p.setNumeroPainel(req.numeroPainel());
        p.setLocalizacao(req.localizacao());
        return ResponseEntity.ok(painelRepository.save(p));
    }

    @GetMapping("/painel/{agenciaId}")
    public List<Painel> listarPaineis(@PathVariable String agenciaId) {
        return painelRepository.findByAgenciaId(agenciaId);
    }

    @DeleteMapping("/painel/{id}")
    public ResponseEntity<Void> excluirPainel(@PathVariable Integer id) {
        painelRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/estacao")
    public ResponseEntity<Estacao> criarEstacao(@RequestBody EstacaoRequest req) {
        Painel painel = painelRepository.findById(req.painelId())
                .orElse(null);
        if (painel == null) {
            return ResponseEntity.badRequest().build();
        }
        Estacao e = new Estacao();
        e.setAgenciaId(req.agenciaId());
        e.setTipoEstacao(req.tipoEstacao());
        e.setNumeroEstacao(req.numeroEstacao());
        e.setLocalizacao(req.localizacao());
        e.setPainel(painel);
        return ResponseEntity.ok(estacaoRepository.save(e));
    }

    @GetMapping("/estacao/{agenciaId}")
    public List<Estacao> listarEstacoes(@PathVariable String agenciaId) {
        return estacaoRepository.findByAgenciaId(agenciaId);
    }

    @DeleteMapping("/estacao/{id}")
    public ResponseEntity<Void> excluirEstacao(@PathVariable Integer id) {
        estacaoRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/atendentes/{agenciaId}")
    public ResponseEntity<List<Map<String, Object>>> listarAtendentes(@PathVariable String agenciaId) {
        return ResponseEntity.ok(keycloakAdminService.listarAtendentes(agenciaId));
    }

    @GetMapping("/servicos")
    public List<Servico> listarServicos() {
        return servicoRepository.findAll();
    }
}
