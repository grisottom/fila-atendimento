package com.fila.apiatendimento.service;

import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Lógica transacional de reprocessamento de uma mensagem vinda da DLQ.
 *
 * Separado do {@link com.fila.apiatendimento.listener.DlqListener} porque o
 * incremento do contador anti-loop, a verificação do teto e o reset da flag do
 * outbox precisam ocorrer na **mesma transação** — e um método `@Transactional`
 * só é interceptado pelo proxy do Spring quando chamado de outro bean (o listener).
 */
@Service
public class DlqReprocessor {

    private static final Logger log = LoggerFactory.getLogger(DlqReprocessor.class);

    /** Status terminal para itens que esgotaram as tentativas de reprocessamento via DLQ. */
    public static final String STATUS_FALHA_DLQ = "FALHA_DLQ";

    private final FilaAtendimentoRepository filaRepository;

    public DlqReprocessor(FilaAtendimentoRepository filaRepository) {
        this.filaRepository = filaRepository;
    }

    /**
     * Processa uma mensagem da DLQ identificada pelo triagemUuid.
     *
     * Numa única transação: valida idempotência, incrementa o contador anti-loop
     * ({@code tentativas_dlq}) e decide entre reprocessar (reset da flag do outbox,
     * que republica no próximo ciclo) ou declarar falha definitiva (status
     * {@link #STATUS_FALHA_DLQ}).
     *
     * Se o banco estiver indisponível, qualquer operação aqui lança e a transação
     * (incluindo a sessão JMS transacionada do listener) sofre rollback — a mensagem
     * permanece na DLQ para nova tentativa.
     *
     * @param maxReprocessos teto de reprocessamentos antes da falha definitiva.
     */
    @Transactional
    public void reprocessar(UUID triagemUuid, int maxReprocessos) {
        Optional<FilaAtendimento> opt = filaRepository.findByTriagemUuid(triagemUuid);
        if (opt.isEmpty()) {
            log.warn("DLQ: FilaAtendimento inexistente para triagemUuid={}, descartando", triagemUuid);
            return; // nada a reprocessar
        }

        FilaAtendimento fila = opt.get();

        // Idempotência: item já saiu de AGUARDANDO (chamado/cancelado/etc.).
        if (!"AGUARDANDO".equals(fila.getStatus())) {
            log.info("DLQ: filaId={} já em status={}, descartando (idempotência)",
                    fila.getId(), fila.getStatus());
            return;
        }

        int tentativas = fila.getTentativasDlq() + 1;
        fila.setTentativasDlq(tentativas);

        if (tentativas > maxReprocessos) {
            // Falha definitiva: quebra o loop DLQ -> fila -> DLQ. O contador do broker
            // (JMSXDeliveryCount) reseta a cada ciclo e não serve para isto — por isso
            // o contador vive no item.
            fila.setStatus(STATUS_FALHA_DLQ);
            filaRepository.save(fila);
            log.error("DLQ: filaId={} (triagemUuid={}) excedeu maxReprocessos ({} > {}). "
                            + "Marcado {} — intervenção manual necessária.",
                    fila.getId(), triagemUuid, tentativas, maxReprocessos, STATUS_FALHA_DLQ);
            return;
        }

        // Reprocessável: marca para republicação (o OutboxPublisher republica no
        // próximo ciclo) e persiste o contador incrementado na mesma transação.
        fila.setPublicadoNoBroker(false);
        filaRepository.save(fila);
        log.info("DLQ: reprocessando filaId={} (triagemUuid={}, tentativa {}/{})",
                fila.getId(), triagemUuid, tentativas, maxReprocessos);
    }
}
