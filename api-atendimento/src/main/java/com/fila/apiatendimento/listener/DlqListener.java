package com.fila.apiatendimento.listener;

import com.fila.apiatendimento.service.DlqReprocessor;
import jakarta.annotation.PostConstruct;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Processa mensagens que o broker moveu para a DLQ após esgotar
 * {@code max-delivery-attempts} (poison messages do fluxo "chamar próximo").
 *
 * Difere dos demais listeners (Replay/Heartbeat) em dois pontos essenciais, pela
 * natureza da DLQ como última linha de defesa baseada em mensagem:
 *
 * <ol>
 *   <li><b>Sessão transacionada</b>: o ack fica atrelado ao sucesso. Retornar
 *       normalmente do {@code onMessage} comita (remove da DLQ); lançar exceção
 *       faz rollback (mensagem PERMANECE na DLQ para reprocessar).</li>
 *   <li><b>Não engole exceção de falha reprocessável</b>: se o reprocessamento
 *       falhar (ex.: banco fora), a exceção propaga de propósito, para a mensagem
 *       não ser perdida.</li>
 * </ol>
 *
 * A decisão (idempotência, contador anti-loop e reprocessar vs. falha definitiva)
 * fica em {@link DlqReprocessor}, num método transacional único.
 */
@Component
public class DlqListener {

    private static final Logger log = LoggerFactory.getLogger(DlqListener.class);

    private final DlqReprocessor dlqReprocessor;
    private final ConnectionFactory connectionFactory;

    /** Nome da fila de dead-letter configurada no broker (broker.xml: dead-letter-address=DLQ). */
    @Value("${atendimento.dlq-nome:DLQ}")
    private String dlqNome;

    /**
     * Teto de reprocessamentos a partir da DLQ. Avaliado contra o contador
     * PERSISTENTE no item ({@code tentativas_dlq}) — não contra JMSXDeliveryCount,
     * que reseta a cada ciclo de DLQ (comprovado em teste).
     */
    @Value("${atendimento.dlq-max-reprocessos:5}")
    private int maxReprocessos;

    public DlqListener(DlqReprocessor dlqReprocessor,
                       ConnectionFactory connectionFactory) {
        this.dlqReprocessor = dlqReprocessor;
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    public void iniciar() {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName(dlqNome);
        container.setPubSubDomain(false);
        container.setSubscriptionDurable(false);
        // Essencial: ack atrelado ao sucesso. Rollback mantém a mensagem na DLQ.
        container.setSessionTransacted(true);
        container.setMessageListener((MessageListener) this::onMessage);
        container.afterPropertiesSet();
        container.start();
        log.info("DlqListener iniciado na fila {} (maxReprocessos={})", dlqNome, maxReprocessos);
    }

    private void onMessage(Message message) {
        String triagemUuidStr;
        try {
            triagemUuidStr = message.getStringProperty("triagemUuid");
        } catch (Exception e) {
            // Mensagem ilegível: não adianta reprocessar. ACK (sai da DLQ).
            log.error("DLQ: mensagem ilegível, descartando: {}", e.getMessage());
            return;
        }

        if (triagemUuidStr == null || triagemUuidStr.isBlank()) {
            log.error("DLQ: mensagem sem triagemUuid, descartando");
            return; // ACK: irrecuperável
        }

        UUID triagemUuid;
        try {
            triagemUuid = UUID.fromString(triagemUuidStr);
        } catch (IllegalArgumentException e) {
            log.error("DLQ: triagemUuid inválido '{}', descartando", triagemUuidStr);
            return; // ACK: irrecuperável
        }

        // Delega a decisão transacional. Se o banco estiver fora, isto LANÇA e a
        // exceção propaga → rollback da sessão → mensagem permanece na DLQ.
        // Retorno normal => commit => mensagem sai da DLQ.
        dlqReprocessor.reprocessar(triagemUuid, maxReprocessos);
    }
}
