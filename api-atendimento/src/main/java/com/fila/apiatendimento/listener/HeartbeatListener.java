package com.fila.apiatendimento.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fila.apiatendimento.entity.Painel;
import com.fila.apiatendimento.repository.PainelRepository;
import jakarta.annotation.PostConstruct;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class HeartbeatListener {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);

    private final PainelRepository painelRepository;
    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    public HeartbeatListener(PainelRepository painelRepository,
                             ConnectionFactory connectionFactory,
                             ObjectMapper objectMapper) {
        this.painelRepository = painelRepository;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void iniciar() {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName("painel-heartbeat");
        container.setPubSubDomain(false);
        container.setSubscriptionDurable(false);
        container.setMessageListener((MessageListener) message -> {
            try {
                String body = ((TextMessage) message).getText();
                JsonNode json = objectMapper.readTree(body);
                String agenciaId = json.get("agenciaId").asText();
                int painelNumero = json.get("painelId").asInt();
                boolean online = json.has("online") ? json.get("online").asBoolean() : true;
                atualizarHeartbeat(agenciaId, painelNumero, online);
            } catch (Exception e) {
                log.error("Erro ao processar painel-heartbeat: {}", e.getMessage());
            }
        });
        container.afterPropertiesSet();
        container.start();
        log.info("HeartbeatListener iniciado na fila painel-heartbeat");
    }

    @Transactional
    public void atualizarHeartbeat(String agenciaId, int painelNumero, boolean online) {
        Optional<Painel> opt = painelRepository.findByAgenciaIdAndNumeroPainel(agenciaId, painelNumero);
        if (opt.isEmpty()) {
            log.warn("Heartbeat para painel inexistente: agencia={} painel={}", agenciaId, painelNumero);
            return;
        }
        Painel painel = opt.get();
        painel.setUltimoHeartbeat(online ? LocalDateTime.now() : null);
        painelRepository.save(painel);
    }
}
