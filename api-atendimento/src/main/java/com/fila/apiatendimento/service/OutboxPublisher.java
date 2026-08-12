package com.fila.apiatendimento.service;

import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Servico;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long BASE_DELAY_MS = 3000;
    private static final long JITTER_MS = 4000;  // delay entre 3s e 7s

    private final FilaAtendimentoRepository filaRepository;
    private final ServicoRepository servicoRepository;
    private final TriagemService triagemService;
    private final TaskScheduler taskScheduler;

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

                triagemService.publicarNaQueueAgencia(
                        fila.getAgenciaId(),
                        fila.getId(),
                        servico.getPermissaoExigida(),
                        fila.getHorarioAgendado() != null
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
     * Reseta o flag de publicação em transação independente, garantindo commit
     * mesmo que a transação externa sofra rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetarPublicacao(Integer filaId) {
        filaRepository.findById(filaId).ifPresent(f -> {
            f.setPublicadoNoBroker(false);
            filaRepository.save(f);
            log.info("Outbox: resetado publicadoNoBroker para filaId={}", filaId);
        });
    }
}
