package com.fila.apiatendimento.service;

import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Servico;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final FilaAtendimentoRepository filaRepository;
    private final ServicoRepository servicoRepository;
    private final TriagemService triagemService;

    public OutboxPublisher(FilaAtendimentoRepository filaRepository,
                           ServicoRepository servicoRepository,
                           TriagemService triagemService) {
        this.filaRepository = filaRepository;
        this.servicoRepository = servicoRepository;
        this.triagemService = triagemService;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publicarPendentes() {
        List<FilaAtendimento> pendentes = filaRepository
                .findByPublicadoNoBrokerFalseAndStatus("AGUARDANDO");

        for (FilaAtendimento fila : pendentes) {
            try {
                Servico servico = servicoRepository.findById(fila.getServicoId()).orElse(null);
                if (servico == null) {
                    log.warn("Serviço não encontrado para filaId={}, servicoId={}", fila.getId(), fila.getServicoId());
                    continue;
                }

                triagemService.publicarNaQueueAgencia(
                        fila.getAgenciaId(),
                        fila.getId(),
                        servico.getPermissaoExigida(),
                        fila.getHorarioAgendado() != null
                );

                fila.setPublicadoNoBroker(true);
                filaRepository.save(fila);

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
