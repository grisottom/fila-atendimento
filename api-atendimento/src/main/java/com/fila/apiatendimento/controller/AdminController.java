package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.AgenciaRequest;
import com.fila.apiatendimento.dto.EstacaoRequest;
import com.fila.apiatendimento.dto.PainelRequest;
import com.fila.apiatendimento.entity.*;
import com.fila.apiatendimento.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AgenciaRepository agenciaRepository;
    private final PainelRepository painelRepository;
    private final EstacaoRepository estacaoRepository;
    private final ServicoRepository servicoRepository;
    private final AtendenteRepository atendenteRepository;
    private final PainelServicoRepository painelServicoRepository;

    public AdminController(AgenciaRepository agenciaRepository,
                           PainelRepository painelRepository,
                           EstacaoRepository estacaoRepository,
                           ServicoRepository servicoRepository,
                           AtendenteRepository atendenteRepository,
                           PainelServicoRepository painelServicoRepository) {
        this.agenciaRepository = agenciaRepository;
        this.painelRepository = painelRepository;
        this.estacaoRepository = estacaoRepository;
        this.servicoRepository = servicoRepository;
        this.atendenteRepository = atendenteRepository;
        this.painelServicoRepository = painelServicoRepository;
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
        Estacao e = new Estacao();
        e.setAgenciaId(req.agenciaId());
        e.setTipoEstacao(req.tipoEstacao());
        e.setNumeroEstacao(req.numeroEstacao());
        e.setLocalizacao(req.localizacao());
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
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listarAtendentes(@PathVariable String agenciaId) {
        List<Atendente> todos = atendenteRepository.findByAgenciaId(agenciaId);
        List<Map<String, Object>> resultado = todos.stream()
                .map(a -> {
                    List<String> perms = a.getPermissoes().stream()
                            .map(PermissaoAtendente::getPermissao)
                            .toList();
                    return Map.<String, Object>of("username", a.getUsername(), "roles", perms);
                })
                .toList();
        return ResponseEntity.ok(resultado);
    }

    @PostMapping("/atendentes/{agenciaId}")
    @Transactional
    public ResponseEntity<Void> salvarPermissoes(
            @PathVariable String agenciaId,
            @RequestBody Map<String, List<String>> body) {
        String username = body.get("username") != null && !body.get("username").isEmpty()
                ? body.get("username").get(0) : null;
        List<String> permissoes = body.get("permissoes");
        if (username == null || permissoes == null) {
            return ResponseEntity.badRequest().build();
        }
        Atendente atendente = atendenteRepository.findByUsernameAndAgenciaId(username, agenciaId)
                .orElseGet(() -> {
                    Atendente novo = new Atendente();
                    novo.setUsername(username);
                    novo.setAgenciaId(agenciaId);
                    return novo;
                });
        atendente.getPermissoes().clear();
        for (String perm : permissoes) {
            PermissaoAtendente pa = new PermissaoAtendente();
            pa.setAtendente(atendente);
            pa.setPermissao(perm);
            atendente.getPermissoes().add(pa);
        }
        atendenteRepository.save(atendente);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/atendentes/{agenciaId}/{username}")
    @Transactional
    public ResponseEntity<Void> atualizarPermissoes(
            @PathVariable String agenciaId,
            @PathVariable String username,
            @RequestBody List<String> permissoes) {
        Atendente atendente = atendenteRepository.findByUsernameAndAgenciaId(username, agenciaId)
                .orElseThrow(() -> new RuntimeException("Atendente não encontrado: " + username));
        atendente.getPermissoes().clear();
        for (String perm : permissoes) {
            PermissaoAtendente pa = new PermissaoAtendente();
            pa.setAtendente(atendente);
            pa.setPermissao(perm);
            atendente.getPermissoes().add(pa);
        }
        atendenteRepository.save(atendente);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/atendentes/{agenciaId}/{username}")
    @Transactional
    public ResponseEntity<Void> removerAtendente(
            @PathVariable String agenciaId,
            @PathVariable String username) {
        atendenteRepository.findByUsernameAndAgenciaId(username, agenciaId)
                .ifPresent(atendenteRepository::delete);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/servicos")
    public List<Servico> listarServicos() {
        return servicoRepository.findAll();
    }

    // ─── Painéis ↔ Serviços ──────────────────────────────────

    @GetMapping("/paineis-servicos/{painelId}")
    public List<PainelServico> listarServicosDoPainel(@PathVariable Integer painelId) {
        return painelServicoRepository.findByPainelId(painelId);
    }

    @PostMapping("/paineis-servicos")
    @Transactional
    public ResponseEntity<PainelServico> associarServicoPainel(@RequestBody Map<String, Object> body) {
        Integer painelId = (Integer) body.get("painelId");
        String servicoId = (String) body.get("servicoId");
        if (painelId == null || servicoId == null) {
            return ResponseEntity.badRequest().build();
        }
        Painel painel = painelRepository.findById(painelId).orElse(null);
        if (painel == null) {
            return ResponseEntity.badRequest().build();
        }
        PainelServico ps = new PainelServico();
        ps.setPainel(painel);
        ps.setServicoId(servicoId);
        return ResponseEntity.ok(painelServicoRepository.save(ps));
    }

    @DeleteMapping("/paineis-servicos/{painelId}/{servicoId}")
    @Transactional
    public ResponseEntity<Void> desassociarServicoPainel(
            @PathVariable Integer painelId,
            @PathVariable String servicoId) {
        painelServicoRepository.deleteByPainelIdAndServicoId(painelId, servicoId);
        return ResponseEntity.noContent().build();
    }
}
