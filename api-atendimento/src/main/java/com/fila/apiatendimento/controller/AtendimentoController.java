package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.dto.ChamarProximoRequest;
import com.fila.apiatendimento.entity.Estacao;
import com.fila.apiatendimento.entity.Servico;
import com.fila.apiatendimento.repository.AtendenteRepository;
import com.fila.apiatendimento.repository.EstacaoRepository;
import com.fila.apiatendimento.service.AtendimentoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/atendimento")
public class AtendimentoController {

    private final AtendimentoService atendimentoService;
    private final EstacaoRepository estacaoRepository;
    private final AtendenteRepository atendenteRepository;

    public AtendimentoController(AtendimentoService atendimentoService,
                                 EstacaoRepository estacaoRepository,
                                 AtendenteRepository atendenteRepository) {
        this.atendimentoService = atendimentoService;
        this.estacaoRepository = estacaoRepository;
        this.atendenteRepository = atendenteRepository;
    }

    @PostMapping("/chamar")
    public ResponseEntity<AtendimentoResponse> chamarProximo(
            @RequestBody ChamarProximoRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        // A agência é obtida da estação informada
        Estacao estacao = estacaoRepository.findById(request.estacaoId())
                .orElseThrow(() -> new RuntimeException("Estação não encontrada: " + request.estacaoId()));
        List<String> permissoes = obterPermissoes(username, estacao.getAgenciaId(), jwt);
        return ResponseEntity.ok(atendimentoService.chamarProximo(request.estacaoId(), username, permissoes));
    }

    @GetMapping("/ativo")
    public ResponseEntity<AtendimentoResponse> buscarAtivo(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        AtendimentoResponse ativo = atendimentoService.buscarAtivo(username);
        if (ativo == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ativo);
    }

    @PostMapping("/ausentar/{id}")
    public ResponseEntity<AtendimentoResponse> ausentar(@PathVariable Integer id) {
        return ResponseEntity.ok(atendimentoService.ausentar(id));
    }

    @PostMapping("/iniciar/{id}")
    public ResponseEntity<AtendimentoResponse> iniciar(@PathVariable Integer id) {
        return ResponseEntity.ok(atendimentoService.iniciarAtendimento(id));
    }

    @PostMapping("/finalizar/{id}")
    public ResponseEntity<AtendimentoResponse> finalizar(@PathVariable Integer id) {
        return ResponseEntity.ok(atendimentoService.finalizarAtendimento(id));
    }

    @PostMapping("/cancelar/{id}")
    public ResponseEntity<AtendimentoResponse> cancelar(@PathVariable Integer id) {
        return ResponseEntity.ok(atendimentoService.cancelarAtendimento(id));
    }

    @GetMapping("/estacoes/{agenciaId}")
    public List<Estacao> listarEstacoes(@PathVariable String agenciaId) {
        return estacaoRepository.findByAgenciaId(agenciaId);
    }

    @GetMapping("/meus-servicos")
    public ResponseEntity<List<Servico>> meusServicos(
            @RequestParam String agenciaId,
            @AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        List<String> permissoes = obterPermissoes(username, agenciaId, jwt);
        return ResponseEntity.ok(atendimentoService.listarServicosPorPermissoes(permissoes));
    }

    @GetMapping("/fila-disponivel")
    public ResponseEntity<List<AtendimentoResponse>> filaDisponivel(
            @RequestParam String agenciaId,
            @AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        List<String> permissoes = obterPermissoes(username, agenciaId, jwt);
        return ResponseEntity.ok(atendimentoService.listarFilaDisponivel(agenciaId, permissoes));
    }

    /**
     * Obtém as permissões de atendimento do banco de dados.
     * Para admin, retorna todas as permissões (basica, normal, especial).
     */
    private List<String> obterPermissoes(String username, String agenciaId, Jwt jwt) {
        if (isAdmin(jwt)) {
            return List.of("basica", "normal", "especial");
        }
        if (agenciaId == null || agenciaId.isBlank()) {
            return Collections.emptyList();
        }
        return atendenteRepository.findPermissoesByUsernameAndAgenciaId(username, agenciaId);
    }

    private boolean isAdmin(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return false;
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles != null && roles.contains("admin");
    }
}
