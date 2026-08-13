package com.fila.apiatendimento.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.entity.Estacao;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Painel;
import com.fila.apiatendimento.entity.PainelServico;
import com.fila.apiatendimento.entity.Servico;
import com.fila.apiatendimento.repository.EstacaoRepository;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.PainelServicoRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AtendimentoService {

    private static final Logger log = LoggerFactory.getLogger(AtendimentoService.class);

    private final FilaAtendimentoRepository filaAtendimentoRepository;
    private final EstacaoRepository estacaoRepository;
    private final ServicoRepository servicoRepository;
    private final PainelServicoRepository painelServicoRepository;
    private final JmsTemplate jmsTemplate;
    private final JmsTemplate jmsQueueTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisher outboxPublisher;

    public AtendimentoService(FilaAtendimentoRepository filaRepository,
                              EstacaoRepository estacaoRepository,
                              ServicoRepository servicoRepository,
                              PainelServicoRepository painelServicoRepository,
                              JmsTemplate jmsTemplate,
                              @Qualifier("jmsQueueTemplate") JmsTemplate jmsQueueTemplate,
                              ObjectMapper objectMapper,
                              OutboxPublisher outboxPublisher) {
        this.filaAtendimentoRepository = filaRepository;
        this.estacaoRepository = estacaoRepository;
        this.servicoRepository = servicoRepository;
        this.painelServicoRepository = painelServicoRepository;
        this.jmsTemplate = jmsTemplate;
        this.jmsQueueTemplate = jmsQueueTemplate;
        this.objectMapper = objectMapper;
        this.outboxPublisher = outboxPublisher;
    }

    public AtendimentoResponse buscarAtivo(String username) {
        return filaAtendimentoRepository.findFirstByAtendenteUsernameAndStatusInOrderByHorarioChamadaDesc(username, List.of("CHAMANDO", "EM_ATENDIMENTO"))
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

        // Verifica se o atendente já tem uma chamada ativa (idempotência)
        List<FilaAtendimento> emChamada = filaAtendimentoRepository.findByAgenciaIdAndStatusIn(
                estacao.getAgenciaId(), List.of("CHAMANDO"));
        FilaAtendimento chamadaAtiva = emChamada.stream()
                .filter(f -> username.equals(f.getAtendenteUsername()))
                .findFirst()
                .orElse(null);

        if (chamadaAtiva != null) {
            publicarNosPaineisDoServico(chamadaAtiva, estacao, "CHAMANDO");
            return toResponse(chamadaAtiva, estacao.getNomeExibicao());
        }

        // Consome a próxima mensagem da fila do broker com selector de permissões
        String queueAgencia = "agencia." + estacao.getAgenciaId() + ".fila";
        String selector = "permissao IN (" +
                permissoes.stream().map(p -> "'" + p + "'").collect(Collectors.joining(",")) + ")";

        Message message = jmsQueueTemplate.receiveSelected(queueAgencia, selector);

        if (message == null) {
            log.warn("Nenhum atendimento na fila: agencia={}, estacao={}, username={}, permissoes={}",
                    estacao.getAgenciaId(), estacaoId, username, permissoes);
            throw new RuntimeException("Nenhum atendimento na fila");
        }

        Integer filaId = null;
        try {
            filaId = message.getIntProperty("filaAtendimentoId");
            final Integer id = filaId;

            FilaAtendimento proximo = filaAtendimentoRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Atendimento não encontrado no banco: " + id));

            // Idempotência: descarta mensagens duplicadas
            if (!"AGUARDANDO".equals(proximo.getStatus())) {
                log.info("Mensagem reentregue para filaId={} com status={}, descartando", filaId, proximo.getStatus());
                return chamarProximo(estacaoId, username, permissoes);
            }

            // Verifica se o serviço está associado a pelo menos um painel
            List<PainelServico> paineisServico = painelServicoRepository.findByServicoId(proximo.getServicoId());
            if (paineisServico.isEmpty()) {
                // Reseta para o outbox republicar (devolve à fila)
                outboxPublisher.resetarPublicacao(id);
                throw new RuntimeException("Serviço '" + proximo.getServicoId() + "' não está associado a nenhum painel");
            }

            proximo.setStatus("CHAMANDO");
            proximo.setEstacaoId(estacaoId);
            proximo.setAtendenteUsername(username);
            proximo.setHorarioChamada(LocalDateTime.now());

            filaAtendimentoRepository.save(proximo);

            publicarNosPaineisDoServico(proximo, estacao, "CHAMANDO");

            return toResponse(proximo, estacao.getNomeExibicao());

        } catch (Exception e) {
            log.warn("Erro no chamarProximo para filaId={}: {}. Resetando publicação.", filaId, e.getMessage());
            if (filaId != null) {
                outboxPublisher.resetarPublicacao(filaId);
            }
            throw new RuntimeException("Erro ao processar mensagem da fila", e);
        }
    }

    @Transactional
    public AtendimentoResponse ausentar(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaAtendimentoRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Estacao estacao = fila.getEstacaoId() != null ? estacaoRepository.findById(fila.getEstacaoId()).orElse(null) : null;

        Integer maxPosicao = filaAtendimentoRepository.findMaxPosicaoFila(fila.getAgenciaId()) + 1;
        fila.setStatus("AUSENTE");
        fila.setPosicaoFila(maxPosicao);
        filaAtendimentoRepository.save(fila);

        publicarNosPaineisDoServico(fila, estacao, "AUSENTE");

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    @Transactional
    public AtendimentoResponse iniciarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaAtendimentoRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        fila.setStatus("EM_ATENDIMENTO");
        fila.setHorarioInicioAtendimento(LocalDateTime.now());
        filaAtendimentoRepository.save(fila);

        Estacao estacao = fila.getEstacaoId() != null ? estacaoRepository.findById(fila.getEstacaoId()).orElse(null) : null;
        publicarNosPaineisDoServico(fila, estacao, "EM_ATENDIMENTO");

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    @Transactional
    public AtendimentoResponse finalizarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaAtendimentoRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Integer estacaoIdSalvo = fila.getEstacaoId();
        Estacao estacao = estacaoIdSalvo != null ? estacaoRepository.findById(estacaoIdSalvo).orElse(null) : null;

        fila.setStatus("FINALIZADO");
        fila.setHorarioFimAtendimento(LocalDateTime.now());
        filaAtendimentoRepository.save(fila);

        publicarNosPaineisDoServico(fila, estacao, "FINALIZADO");

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    @Transactional
    public AtendimentoResponse cancelarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaAtendimentoRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Estacao estacao = fila.getEstacaoId() != null ? estacaoRepository.findById(fila.getEstacaoId()).orElse(null) : null;

        fila.setStatus("CANCELADO");
        fila.setHorarioFimAtendimento(null);
        filaAtendimentoRepository.save(fila);

        publicarNosPaineisDoServico(fila, estacao, "CANCELADO");

        return toResponse(fila, estacao != null ? estacao.getNomeExibicao() : null);
    }

    /**
     * Publica evento em todos os painéis associados ao serviço do atendimento.
     */
    private void publicarNosPaineisDoServico(FilaAtendimento fila, Estacao estacao, String status) {
        List<PainelServico> paineisServico = painelServicoRepository.findByServicoId(fila.getServicoId());
        if (paineisServico.isEmpty()) {
            log.warn("Serviço {} sem painéis associados, evento {} não publicado para filaId={}",
                    fila.getServicoId(), status, fila.getId());
            return;
        }

        String estacaoNome = estacao != null ? estacao.getNomeExibicao() : "N/A";

        for (PainelServico ps : paineisServico) {
            Painel painel = ps.getPainel();
            String topico = "agencia." + fila.getAgenciaId() + ".painel." + painel.getNumeroPainel();
            String json;
            try {
                json = objectMapper.writeValueAsString(Map.of(
                        "agenciaId", fila.getAgenciaId(),
                        "painelId", painel.getNumeroPainel(),
                        "senha", fila.getSenha(),
                        "nomePessoa", fila.getNomePessoa(),
                        "estacao", estacaoNome,
                        "status", status
                ));
            } catch (Exception e) {
                throw new RuntimeException("Erro ao serializar mensagem para o broker", e);
            }
            jmsTemplate.send(topico, session -> session.createTextMessage(json));
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
        return filaAtendimentoRepository.findFilaDisponivel(agenciaId, permissoes)
                .stream()
                .map(f -> toResponse(f, null))
                .toList();
    }
}
