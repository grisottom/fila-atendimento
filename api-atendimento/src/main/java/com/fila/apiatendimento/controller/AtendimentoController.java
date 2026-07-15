package com.fila.apiatendimento.controller;

import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.dto.ChamarProximoRequest;
import com.fila.apiatendimento.entity.Estacao;
import com.fila.apiatendimento.entity.Servico;
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

    public AtendimentoController(AtendimentoService atendimentoService, EstacaoRepository estacaoRepository) {
        this.atendimentoService = atendimentoService;
        this.estacaoRepository = estacaoRepository;
    }

    @PostMapping("/chamar")
    public ResponseEntity<AtendimentoResponse> chamarProximo(
            @RequestBody ChamarProximoRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        List<String> permissoes = extrairPermissoes(jwt);
        return ResponseEntity.ok(atendimentoService.chamarProximo(request.estacaoId(), username, permissoes));
    }

    @GetMapping("/ativo")
    public ResponseEntity<AtendimentoResponse> buscarAtivo(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        AtendimentoResponse ativo = atendimentoService.buscarAtivo(username);
        if (ativo == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ativo);
    }

    @PostMapping("/rechamar/{id}")
    public ResponseEntity<Void> rechamar(@PathVariable Integer id) {
        atendimentoService.rechamar(id);
        return ResponseEntity.ok().build();
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
    public ResponseEntity<List<Servico>> meusServicos(@AuthenticationPrincipal Jwt jwt) {
        List<String> permissoes = extrairPermissoes(jwt);
        return ResponseEntity.ok(atendimentoService.listarServicosPorPermissoes(permissoes));
    }

    @GetMapping("/fila-disponivel")
    public ResponseEntity<List<AtendimentoResponse>> filaDisponivel(
            @RequestParam String agenciaId,
            @AuthenticationPrincipal Jwt jwt) {
        List<String> permissoes = extrairPermissoes(jwt);
        return ResponseEntity.ok(atendimentoService.listarFilaDisponivel(agenciaId, permissoes));
    }

    @SuppressWarnings("unchecked")
    private List<String> extrairPermissoes(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return Collections.emptyList();
        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles != null ? roles : Collections.emptyList();
    }
}
