package com.fila.apipainel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class HeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatPublisher.class);

    private final PainelSseService painelSseService;
    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    public HeartbeatPublisher(PainelSseService painelSseService,
                              ConnectionFactory connectionFactory,
                              ObjectMapper objectMapper) {
        this.painelSseService = painelSseService;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 10_000)
    public void publicarHeartbeats() {
        Set<String> paineisAtivos = painelSseService.getPaineisConectados();
        if (paineisAtivos.isEmpty()) return;

        JmsTemplate filaTemplate = new JmsTemplate(Objects.requireNonNull(connectionFactory));
        filaTemplate.setPubSubDomain(false);

        for (String chave : paineisAtivos) {
            try {
                String[] partes = chave.split(":");
                String agenciaId = partes[0];
                int painelId = Integer.parseInt(partes[1]);

                String json = objectMapper.writeValueAsString(Map.of(
                        "agenciaId", agenciaId,
                        "painelId", painelId,
                        "online", true
                ));
                filaTemplate.send("painel-heartbeat", session -> session.createTextMessage(json));
            } catch (Exception e) {
                log.warn("Erro ao publicar heartbeat para {}: {}", chave, e.getMessage());
            }
        }
        log.info("Heartbeat publicado para {} painéis ativos", paineisAtivos.size());
    }
}
