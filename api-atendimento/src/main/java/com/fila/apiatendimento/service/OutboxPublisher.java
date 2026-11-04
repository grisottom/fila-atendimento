package com.fila.apiatendimento.service;

import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Servico;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long BASE_DELAY_MS = 3000;
    private static final long JITTER_MS = 4000;  // delay entre 3s e 7s

    // "Em curso": itens que, se existirem, significam que a agência NÃO está parada.
    private static final List<String> STATUS_EM_CURSO = List.of("CHAMANDO", "EM_ATENDIMENTO");

    private final FilaAtendimentoRepository filaRepository;
    private final ServicoRepository servicoRepository;
    private final TriagemService triagemService;
    private final TaskScheduler taskScheduler;

    // Intervalo típico (min) entre horários da agenda. A janela de liberação de um
    // retido é 2 × este valor. Ver application.yml (atendimento.intervalo-agendamento-min).
    @Value("${atendimento.intervalo-agendamento-min:15}")
    private int intervaloAgendamentoMin;

    public OutboxPublisher(FilaAtendimentoRepository filaRepository,
                           ServicoRepository servicoRepository,
                           TriagemService triagemService,
                           TaskScheduler taskScheduler) {
        this.filaRepository = filaRepository;
        this.servicoRepository = servicoRepository;
        this.triagemService = triagemService;
        this.taskScheduler = taskScheduler;
    }

    // Usa TaskScheduler com jitter em vez de @Scheduled(fixedDelay) para evitar
    // que múltiplas réplicas disparem no mesmo instante e concorram desnecessariamente.
    @PostConstruct
    public void iniciar() {
        agendarProximaExecucao();
    }

    private void agendarProximaExecucao() {
        long delay = BASE_DELAY_MS + ThreadLocalRandom.current().nextLong(JITTER_MS);
        taskScheduler.schedule(this::executar, Instant.now().plus(Duration.ofMillis(delay)));
    }

    private void executar() {
        try {
            publicarPendentes();
            liberarAgendadosFuturos();
        } finally {
            agendarProximaExecucao();
        }
    }

    @Transactional
    public void publicarPendentes() {
        List<FilaAtendimento> pendentes = filaRepository
            .findByPublicadoNoBrokerFalseAndStatus("AGUARDANDO");

        for (FilaAtendimento fila : pendentes) {
            try {
                String servicoId = fila.getServicoId();
                if (servicoId == null) {
                    log.warn("servicoId nulo para filaId={}", fila.getId());
                    continue;
                }
                Servico servico = servicoRepository.findById(servicoId).orElse(null);
                if (servico == null) {
                    log.warn("Serviço não encontrado para filaId={}, servicoId={}", fila.getId(), servicoId);
                    continue;
                }

                int prioridade = PrioridadeCalculator.nivelJms(
                        fila.getCategoriaPrioridade(), fila.getHorarioAgendado() != null);
                triagemService.publicarNaQueueAgencia(
                        fila.getAgenciaId(),
                        fila.getTriagemUuid(),
                        servico.getPermissaoExigida(),
                        prioridade
                );

                // Recarrega do banco para pegar version atualizada (atendente pode ter modificado)
                FilaAtendimento atualizado = filaRepository.findById(fila.getId()).orElse(null);
                if (atualizado != null && !atualizado.isPublicadoNoBroker()) {
                    atualizado.setPublicadoNoBroker(true);
                    filaRepository.save(atualizado);
                }

                log.info("Outbox: publicado filaId={} na queue da agência {}", fila.getId(), fila.getAgenciaId());
            } catch (Exception e) {
                log.warn("Outbox: falha ao publicar filaId={}: {}", fila.getId(), e.getMessage());
                // Próxima execução tenta novamente
            }
        }
    }

    /**
     * Pré-triagem: libera os agendados RETIDOS (status AGENDADO_FUTURO) que ficaram
     * elegíveis, publicando-os na fila do broker (e mudando o status para AGUARDANDO).
     *
     * Percorre os retidos ORDENADOS por horário agendado (ASC). Assim, o lote que se
     * torna elegível no mesmo ciclo entra na fila JMS na ordem de horário — a fila é
     * FIFO entre mensagens de mesma prioridade (todos os agendados têm prioridade 9).
     *
     * Um retido é elegível quando:
     *   (1) entrou na JANELA: now() >= horarioAgendado − (2 × intervalo); OU
     *   (2) a agência está PARADA: nenhum item CHAMANDO/EM_ATENDIMENTO na agência
     *       (não faz sentido segurar o antecipado se não há ninguém sendo atendido).
     *
     * Esta varredura é SEPARADA da de "falha de publicação" ({@link #publicarPendentes()},
     * que trata AGUARDANDO + publicado=false). Um retido NÃO é publicado por aquela,
     * pois seu status é AGENDADO_FUTURO.
     */
    @Transactional
    public void liberarAgendadosFuturos() {
        List<FilaAtendimento> retidos = filaRepository
                .findByStatusOrderByHorarioAgendadoAsc(FilaAtendimento.STATUS_AGENDADO_FUTURO);
        if (retidos.isEmpty()) {
            return;
        }

        LocalDateTime agora = LocalDateTime.now();
        long janelaMin = 2L * intervaloAgendamentoMin;

        // Cache por agência da checagem "agência parada" (evita repetir o count no
        // mesmo ciclo). null = ainda não consultado; TRUE/FALSE = resultado.
        java.util.Map<String, Boolean> agenciaParadaCache = new java.util.HashMap<>();

        for (FilaAtendimento fila : retidos) {
            try {
                LocalDateTime horarioAgendado = fila.getHorarioAgendado();
                // Segurança: um AGENDADO_FUTURO sem horário não deveria existir; se
                // ocorrer, libera (não há como calcular janela).
                boolean entrouNaJanela = horarioAgendado == null
                        || !agora.isBefore(horarioAgendado.minusMinutes(janelaMin));

                boolean elegivel = entrouNaJanela;
                if (!elegivel) {
                    Boolean parada = agenciaParadaCache.computeIfAbsent(
                            fila.getAgenciaId(),
                            ag -> filaRepository.countByAgenciaIdAndStatusIn(ag, STATUS_EM_CURSO) == 0);
                    elegivel = Boolean.TRUE.equals(parada);
                }

                if (!elegivel) {
                    continue; // segue retido; reavalia no próximo ciclo
                }

                String servicoId = fila.getServicoId();
                Servico servico = servicoId != null ? servicoRepository.findById(servicoId).orElse(null) : null;
                if (servico == null) {
                    log.warn("AGENDADO_FUTURO: serviço não encontrado para filaId={}, servicoId={}",
                            fila.getId(), servicoId);
                    continue;
                }

                // Publica na fila e promove a AGUARDANDO. Nível JMS derivado da
                // categoria de prioridade do item (é sempre agendado, pois só
                // agendados são retidos como AGENDADO_FUTURO).
                int prioridade = PrioridadeCalculator.nivelJms(fila.getCategoriaPrioridade(), true);
                triagemService.publicarNaQueueAgencia(
                        fila.getAgenciaId(), fila.getTriagemUuid(), servico.getPermissaoExigida(), prioridade);

                fila.setStatus(FilaAtendimento.STATUS_AGUARDANDO);
                fila.setPublicadoNoBroker(true);
                filaRepository.save(fila);

                log.info("Pré-triagem: liberado AGENDADO_FUTURO filaId={} horario={} (gatilho={})",
                        fila.getId(), horarioAgendado, entrouNaJanela ? "janela" : "agencia-parada");
            } catch (Exception e) {
                log.warn("Pré-triagem: falha ao liberar AGENDADO_FUTURO filaId={}: {}", fila.getId(), e.getMessage());
                // Próximo ciclo tenta novamente
            }
        }
    }

    /**
     * Reseta o flag de publicação em transação independente, garantindo commit
     * mesmo que a transação externa sofra rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetarPublicacao(@NonNull Integer filaId) {
        filaRepository.findById(filaId).ifPresent(f -> {
            f.setPublicadoNoBroker(false);
            filaRepository.save(f);
            log.info("Outbox: resetado publicadoNoBroker para filaId={}", filaId);
        });
    }
}
