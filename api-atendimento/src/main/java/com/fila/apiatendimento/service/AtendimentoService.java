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
            String aviso = null;
            boolean painelAtivo = painelServicoRepository.existePainelAtivoParaServico(
                    chamadaAtiva.getServicoId(), estacao.getAgenciaId(), LocalDateTime.now().minusMinutes(10));
            if (!painelAtivo) {
                aviso = "Nenhum painel ativo para o serviço '" + chamadaAtiva.getServicoId() + "' nesta agência";
                log.warn("chamarProximo (chamadaAtiva): {}", aviso);
            }
            return new AtendimentoResponse(chamadaAtiva.getId(), chamadaAtiva.getSenha(), chamadaAtiva.getCpf(),
                    chamadaAtiva.getNomePessoa(), chamadaAtiva.getServicoId(), chamadaAtiva.getStatus(),
                    estacao.getNomeExibicao(), aviso);
        }

        // Sem permissões não há nada a consumir: evita montar um selector inválido
        // ("permissao IN ()") que o broker rejeita com AMQ229020.
        if (permissoes == null || permissoes.isEmpty()) {
            log.warn("Atendente sem permissões: agencia={}, estacao={}, username={}",
                    estacao.getAgenciaId(), estacaoId, username);
            throw new RuntimeException("Nenhum atendimento na fila");
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
            String triagemUuidStr = message.getStringProperty("triagemUuid");
            final java.util.UUID triagemUuid = java.util.UUID.fromString(triagemUuidStr);

            // CASO "MENSAGEM SEM LINHA" (órfã): a mensagem existe no broker mas não há
            // FilaAtendimento correspondente. Duas origens possíveis:
            //   a) transitório: a triagem publicou e o OutboxPublisher ainda vai gravar/
            //      reconciliar (ou o commit da triagem está em curso) — basta aguardar;
            //   b) permanente: a transação da triagem sofreu rollback APÓS o publish
            //      (publish antes do commit), então a linha nunca existirá.
            //
            // Tratamos SEM lançar exceção, para (1) NÃO mascarar a causa no catch genérico
            // ("Erro ao processar mensagem da fila") e (2) NÃO devolver a mensagem à fila
            // em rollback — o que, no caso (b), viraria uma "mensagem-veneno" em loop.
            // A mensagem é consumida (descartada) e devolvemos um aviso DISTINTO para que
            // o chamador aguarde e tente de novo; se for o caso (a), o outbox republica a
            // partir do banco e a próxima tentativa encontra o item.
            FilaAtendimento proximo = filaAtendimentoRepository.findByTriagemUuid(triagemUuid).orElse(null);
            if (proximo == null) {
                log.warn("Mensagem sem linha no banco (triagemUuid={}): descartando e sinalizando indisponibilidade temporária", triagemUuid);
                return new AtendimentoResponse(null, null, null, null, null, "NAO_ENCONTRADO", null,
                        "Atendimento ainda não disponível no banco. Aguarde e tente novamente.");
            }

            // PK técnica, usada no restante do fluxo (reset do outbox, logs, resposta).
            filaId = proximo.getId();

            // Idempotência: mensagem reentregue para um item que já saiu do estado
            // AGUARDANDO (já chamado/atendido/ausente/finalizado/cancelado).
            //
            // Em vez de recorrer internamente para "pegar a próxima" (recursão que
            // acumulava consumos na mesma transação/sessão JMS e não tinha teto),
            // confirmamos o consumo desta duplicata — SEM rollback: o return normal
            // sai limpo do try, não aciona o catch nem o resetarPublicacao, então a
            // mensagem NÃO volta para a fila (é descartada no commit) — e devolvemos
            // ao atendente um aviso significativo do estado real para que ele clique
            // em "chamar próximo" novamente, avançando para a mensagem seguinte.
            if (!"AGUARDANDO".equals(proximo.getStatus())) {
                log.info("Mensagem reentregue para filaId={} com status={}, descartando", filaId, proximo.getStatus());
                String aviso = mensagemReentregue(proximo.getSenha(), proximo.getStatus());
                return new AtendimentoResponse(null, null, null, null, null, proximo.getStatus(), null, aviso);
            }

            // Verifica se o serviço está associado a pelo menos um painel
            List<PainelServico> paineisServico = painelServicoRepository.findByServicoId(proximo.getServicoId());
            if (paineisServico.isEmpty()) {
                // Reseta para o outbox republicar (devolve à fila)
                outboxPublisher.resetarPublicacao(filaId);
                throw new RuntimeException("Serviço '" + proximo.getServicoId() + "' não está associado a nenhum painel");
            }

            proximo.setStatus("CHAMANDO");
            proximo.setEstacaoId(estacaoId);
            proximo.setAtendenteUsername(username);
            proximo.setHorarioChamada(LocalDateTime.now());

            filaAtendimentoRepository.save(proximo);

            publicarNosPaineisDoServico(proximo, estacao, "CHAMANDO");

            // Verifica se há painel ativo para exibir a chamada
            String aviso = null;
            boolean painelAtivo = painelServicoRepository.existePainelAtivoParaServico(
                    proximo.getServicoId(), estacao.getAgenciaId(), LocalDateTime.now().minusMinutes(10));
            if (!painelAtivo) {
                aviso = "Nenhum painel ativo para o serviço '" + proximo.getServicoId() + "' nesta agência";
                log.warn("chamarProximo: {}", aviso);
            }

            return new AtendimentoResponse(proximo.getId(), proximo.getSenha(), proximo.getCpf(),
                    proximo.getNomePessoa(), proximo.getServicoId(), proximo.getStatus(),
                    estacao.getNomeExibicao(), aviso);

        } catch (Exception e) {
            // Preserva a mensagem da causa para não "achatar" situações distintas num
            // texto genérico. O caso órfã (sem linha) já é tratado acima sem exceção;
            // aqui caem falhas reais de processamento (ex.: erro ao salvar, serializar,
            // publicar no painel). Devolver a mensagem original ajuda a diagnosticar.
            log.warn("Erro no chamarProximo para filaId={}: {}", filaId, e.getMessage());
            if (filaId != null) {
                outboxPublisher.resetarPublicacao(filaId);
                log.warn("Resetando publicação do filaId={} para o outbox republicar", filaId);
            }
            String causa = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new RuntimeException("Erro ao processar atendimento: " + causa, e);
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
        List<PainelServico> paineisServico = painelServicoRepository
                .findByServicoIdAndPainelAgenciaId(fila.getServicoId(), fila.getAgenciaId());
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

    /**
     * Monta um aviso significativo quando uma mensagem reentregue aponta para um
     * item que já não está mais AGUARDANDO. A mensagem reflete o estado real do
     * item e orienta o atendente a tentar novamente, já que o retry agora é humano
     * (não há mais recursão interna drenando a fila automaticamente).
     */
    private String mensagemReentregue(String senha, String status) {
        String ref = senha != null ? "A senha " + senha : "A chamada anterior";
        String base = switch (status) {
            case "CHAMANDO" -> ref + " já está sendo chamada por outra estação.";
            case "EM_ATENDIMENTO" -> ref + " já está em atendimento.";
            case "AUSENTE" -> ref + " foi marcada como ausente.";
            case "FINALIZADO" -> ref + " já foi finalizada.";
            case "CANCELADO" -> ref + " foi cancelada.";
            default -> ref + " não está mais aguardando (status atual: " + status + ").";
        };
        return base + " Clique em chamar próximo novamente.";
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
