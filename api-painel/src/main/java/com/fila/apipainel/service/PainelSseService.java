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

        // 2. Define o que acontece quando o Artemis entregar uma mensagem neste tópico
        container.setMessageListener((MessageListener) message -> {
            try {
                String body = ((TextMessage) message).getText();
                emitter.send(SseEmitter.event()
                        .name("painel-update")
                        .data(body));
            } catch (Exception e) {
                log.error("Erro ao enviar SSE para {}: {}", chave, e.getMessage());
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

        // 4. Solicita replay dos atendimentos ativos para este painel
        solicitarReplay(agenciaId, painelId);

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
                sub.emitter().complete();
            } catch (Exception e) {
                log.warn("Erro ao limpar subscription {}: {}", chave, e.getMessage());
            }
            log.info("Painel desconectado: {}", chave);
        }
    }

    private void solicitarReplay(String agenciaId, Integer painelId) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("agenciaId", agenciaId, "painelId", painelId));
            jmsTemplate.send("replay-request", session -> session.createTextMessage(json));
            log.info("Replay solicitado para agencia={} painel={}", agenciaId, painelId);
        } catch (Exception e) {
            log.error("Erro ao solicitar replay: {}", e.getMessage());
        }
    }

    private record PainelSubscription(SseEmitter emitter, DefaultMessageListenerContainer container) {}
}
