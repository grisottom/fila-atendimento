package com.fila.apipainel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fila.apipainel.dto.PainelUpdateDTO;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PainelSseService {

    private static final Logger log = LoggerFactory.getLogger(PainelSseService.class);

    private final JmsTemplate jmsTemplate;
    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    private final Map<String, PainelSubscription> subscriptions = new ConcurrentHashMap<>();

    public PainelSseService(JmsTemplate jmsTemplate, ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.jmsTemplate = jmsTemplate;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    public SseEmitter registrar(String agenciaId, Integer painelId) {
        String chave = agenciaId + ":" + painelId;
        String topico = "agencia." + agenciaId + ".painel." + painelId;

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

         // 1. Cria um container de escuta do Spring JMS em tempo de execução
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName(topico);

        // ATENÇÃO: Diz ao Spring/Artemis que este endereço é um TÓPICO (Pub/Sub), não uma Fila isolada
        container.setPubSubDomain(true);

        // Garante que a assinatura gerada no Artemis é temporária (se desfaz ao desconectar)
        container.setSubscriptionDurable(false);

        // 2. Quando o Artemis entrega uma mensagem neste tópico para este painel, envia via SSE para o cliente
        container.setMessageListener((MessageListener) message -> {
            try {
                String body = ((TextMessage) message).getText();
                PainelSubscription sub = subscriptions.get(chave);
                // ignora se já foi cleanup ou se este container pertence a uma sessão anterior
                if (sub == null || sub.emitter() != emitter) return;
                emitter.send(SseEmitter.event()
                        .name("painel-update")
                        .data(body));
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.toLowerCase().contains("broken pipe") || msg.contains("already completed"))) {
                    log.warn("Cliente desconectou ({}) para {}", msg, chave);
                } else {
                    log.error("Erro ao enviar SSE para {}: {}", chave, msg);
                }
                cleanup(chave);
            }
        });
        container.afterPropertiesSet();
        container.start();

        subscriptions.put(chave, new PainelSubscription(emitter, container));

        // 3. Callbacks de segurança para limpar a memória do Java e do Artemis se o cliente sumir
        emitter.onCompletion(() -> cleanup(chave));
        emitter.onTimeout(() -> cleanup(chave));
        emitter.onError(e -> cleanup(chave));

        // 4. Solicita replay com delay para garantir que o subscriber já está ativo no Artemis
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            replayRequest(agenciaId, painelId);
        }).start();

        log.info("Painel conectado via SSE: {}", chave);

        return emitter;
    }

    public void publicar(PainelUpdateDTO update) throws Exception {
        String topico = "agencia." + update.agenciaId() + ".painel." + update.painelId();
        String json = objectMapper.writeValueAsString(update);

        jmsTemplate.send(topico, session -> session.createTextMessage(json));
        log.info("Mensagem publicada no tópico {}: {}", topico, json);
    }

    private void cleanup(String chave) {
        PainelSubscription sub = subscriptions.remove(chave);
        if (sub != null) {
            try {
                sub.container().stop();
                sub.container().destroy();
            } catch (Exception e) {
                log.warn("Erro ao limpar container {}: {}", chave, e.getMessage());
            }
            try {
                sub.emitter().complete();
            } catch (Exception ignored) {
                // emitter já pode ter completado por conta própria
            }
            log.info("Painel desconectado: {}", chave);
        }
    }

    private void replayRequest(String agenciaId, Integer painelId) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("agenciaId", agenciaId, "painelId", painelId));
            // Envia para fila (não tópico) — pubSubDomain=false garante consumo único pelo ReplayListener
            JmsTemplate filaTemplate = new JmsTemplate(connectionFactory);
            filaTemplate.setPubSubDomain(false);
            filaTemplate.send("replay-request", session -> session.createTextMessage(json));
            log.info("Replay solicitado para agencia={} painel={}", agenciaId, painelId);
        } catch (Exception e) {
            log.error("Erro ao solicitar replay: {}", e.getMessage());
        }
    }

    private record PainelSubscription(SseEmitter emitter, DefaultMessageListenerContainer container) {}
}
