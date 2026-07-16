package com.fila.apiatendimento.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.entity.Estacao;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Painel;
import com.fila.apiatendimento.entity.Servico;
import com.fila.apiatendimento.repository.EstacaoRepository;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AtendimentoService {

    private static final Logger log = LoggerFactory.getLogger(AtendimentoService.class);

    private final FilaAtendimentoRepository filaRepository;
    private final EstacaoRepository estacaoRepository;
    private final ServicoRepository servicoRepository;
    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    public AtendimentoService(FilaAtendimentoRepository filaRepository,
                              EstacaoRepository estacaoRepository,
                              ServicoRepository servicoRepository,
                              JmsTemplate jmsTemplate,
                              ObjectMapper objectMapper) {
        this.filaRepository = filaRepository;
        this.estacaoRepository = estacaoRepository;
        this.servicoRepository = servicoRepository;
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
    }

    public AtendimentoResponse buscarAtivo(String username) {
        return filaRepository.findFirstByAtendenteUsernameAndStatusInOrderByHorarioChamadaDesc(username, List.of("CHAMANDO", "EM_ATENDIMENTO"))
                .map(fila -> {
                    String estacaoNome = fila.getEstacaoId() != null
                            ? estacaoRepository.findById(fila.getEstacaoId()).map(Estacao::getNomeExibicao).orElse(null)
                            : null;
                    return toResponse(fila, estacaoNome);
                })
                .orElse(null);
    }

    @Transactional
    public AtendimentoResponse chamarProximo(Integer estacaoId, String username, List<String> permissoes) {
        Estacao estacao = estacaoRepository.findById(estacaoId)
                .orElseThrow(() -> new RuntimeException("Estação não encontrada: " + estacaoId));

        if (estacao.getPainel() == null) {
            throw new RuntimeException("Estação sem painel associado");
        }

        List<FilaAtendimento> emChamada = filaRepository.findByAgenciaIdAndStatusIn(
                estacao.getAgenciaId(), List.of("CHAMANDO"));
        FilaAtendimento chamadaAtiva = emChamada.stream()
                .filter(f -> username.equals(f.getAtendenteUsername()))
                .findFirst()
                .orElse(null);

        if (chamadaAtiva != null) {
            publicarNoPainel(estacao, chamadaAtiva, "CHAMANDO");
            return toResponse(chamadaAtiva, estacao.getNomeExibicao());
        }

        FilaAtendimento proximo = filaRepository.findProximoParaAtendimento(estacao.getAgenciaId(), permissoes)
                .orElseThrow(() -> new RuntimeException("Nenhum atendimento na fila"));

        proximo.setStatus("CHAMANDO");
        proximo.setEstacaoId(estacaoId);
        proximo.setAtendenteUsername(username);
        proximo.setHorarioChamada(LocalDateTime.now());
        filaRepository.save(proximo);

        publicarNoPainel(estacao, proximo, "CHAMANDO");

        return toResponse(proximo, estacao.getNomeExibicao());
    }

    @Transactional
    public void rechamar(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Estacao estacao = estacaoRepository.findById(fila.getEstacaoId())
                .orElseThrow(() -> new RuntimeException("Estação não encontrada"));

        publicarNoPainel(estacao, fila, "CHAMANDO");
    }

    @Transactional
    public AtendimentoResponse ausentar(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Estacao estacao = fila.getEstacaoId() != null ? estacaoRepository.findById(fila.getEstacaoId()).orElse(null) : null;

        Integer maxPosicao = filaRepository.findMaxPosicaoFila(fila.getAgenciaId()) + 1;
        fila.setStatus("AUSENTE");
        fila.setPosicaoFila(maxPosicao);
        filaRepository.save(fila);

        if (estacao != null) {
            publicarNoPainel(estacao, fila, "AUSENTE");
        }

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    @Transactional
    public AtendimentoResponse iniciarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        fila.setStatus("EM_ATENDIMENTO");
        fila.setHorarioInicioAtendimento(LocalDateTime.now());
        filaRepository.save(fila);

        Estacao estacao = estacaoRepository.findById(fila.getEstacaoId()).orElse(null);
        if (estacao != null) {
            publicarNoPainel(estacao, fila, "EM_ATENDIMENTO");
        }

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    @Transactional
    public AtendimentoResponse finalizarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Integer estacaoIdSalvo = fila.getEstacaoId();
        Estacao estacao = estacaoIdSalvo != null ? estacaoRepository.findById(estacaoIdSalvo).orElse(null) : null;

        fila.setStatus("FINALIZADO");
        fila.setHorarioFimAtendimento(LocalDateTime.now());
        filaRepository.save(fila);

        if (estacao != null) {
            publicarNoPainel(estacao, fila, "FINALIZADO");
        } else {
            log.warn("finalizarAtendimento: estacaoId nulo para atendimento {}, não publicou no painel", atendimentoId);
        }

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    @Transactional
    public AtendimentoResponse cancelarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Estacao estacao = fila.getEstacaoId() != null ? estacaoRepository.findById(fila.getEstacaoId()).orElse(null) : null;

        fila.setStatus("CANCELADO");
        fila.setHorarioFimAtendimento(null);
        filaRepository.save(fila);

        if (estacao != null) {
            publicarNoPainel(estacao, fila, "CANCELADO");
        }

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    private void publicarNoPainel(Estacao estacao, FilaAtendimento fila, String status) {
        Painel painel = estacao.getPainel();
        if (painel == null) return;
        try {
            String topico = "agencia." + estacao.getAgenciaId() + ".painel." + painel.getNumeroPainel();
            String json = objectMapper.writeValueAsString(Map.of(
                    "agenciaId", estacao.getAgenciaId(),
                    "painelId", painel.getNumeroPainel(),
                    "senha", fila.getSenha(),
                    "nomePessoa", fila.getNomePessoa(),
                    "estacao", estacao.getNomeExibicao(),
                    "status", status
            ));
            jmsTemplate.send(topico, session -> session.createTextMessage(json));
        } catch (Exception e) {
            log.error("Erro ao publicar no painel {}: {}", painel.getNumeroPainel(), e.getMessage());
        }
    }

    private AtendimentoResponse toResponse(FilaAtendimento fila, String estacaoNome) {
        return new AtendimentoResponse(fila.getId(), fila.getSenha(), fila.getCpf(), fila.getNomePessoa(),
                fila.getServicoId(), fila.getStatus(), estacaoNome);
    }

    public List<Servico> listarServicosPorPermissoes(List<String> permissoes) {
        return servicoRepository.findByPermissaoExigidaIn(permissoes);
    }

    public List<AtendimentoResponse> listarFilaDisponivel(String agenciaId, List<String> permissoes) {
        if (agenciaId == null || permissoes.isEmpty()) return List.of();
        return filaRepository.findFilaDisponivel(agenciaId, permissoes)
                .stream()
                .map(f -> toResponse(f, null))
                .toList();
    }
}
