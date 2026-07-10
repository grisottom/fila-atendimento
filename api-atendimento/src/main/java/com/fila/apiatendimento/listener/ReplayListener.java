package com.fila.apiatendimento.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Sala;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.SalaRepository;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

@Component
public class ReplayListener {

    private static final Logger log = LoggerFactory.getLogger(ReplayListener.class);

    private final FilaAtendimentoRepository filaRepository;
    private final SalaRepository salaRepository;
    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final JmsTemplate jmsTemplate;

    public ReplayListener(FilaAtendimentoRepository filaRepository,
                          SalaRepository salaRepository,
                          ConnectionFactory connectionFactory,
                          ObjectMapper objectMapper,
                          JmsTemplate jmsTemplate) {
        this.filaRepository = filaRepository;
        this.salaRepository = salaRepository;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.jmsTemplate = jmsTemplate;
    }

    @PostConstruct
    public void iniciar() {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName("replay-request");
        container.setPubSubDomain(true);
        container.setSubscriptionDurable(false);
        container.setMessageListener((MessageListener) message -> {
            try {
                String body = ((TextMessage) message).getText();
                JsonNode json = objectMapper.readTree(body);
                String agenciaId = json.get("agenciaId").asText();
                int painelId = json.get("painelId").asInt();
                replay(agenciaId, painelId);
            } catch (Exception e) {
                log.error("Erro ao processar replay-request: {}", e.getMessage());
            }
        });
        container.afterPropertiesSet();
        container.start();
        log.info("ReplayListener iniciado no tópico replay-request");
    }

    private void replay(String agenciaId, int painelId) {
        List<FilaAtendimento> ativos = filaRepository.findByAgenciaIdAndStatusIn(
                agenciaId, List.of("CHAMANDO", "EM_ATENDIMENTO"));

        for (FilaAtendimento fila : ativos) {
            if (fila.getSalaId() == null) continue;

            Sala sala = salaRepository.findById(fila.getSalaId()).orElse(null);
            if (sala == null) continue;

            boolean painelAssociado = sala.getPaineis().stream()
                    .anyMatch(p -> p.getNumero() == painelId);
            if (!painelAssociado) continue;

            try {
                String topico = "agencia." + agenciaId + ".painel." + painelId;
                String json = objectMapper.writeValueAsString(Map.of(
                        "agenciaId", agenciaId,
                        "painelId", painelId,
                        "senha", fila.getSenha(),
                        "nomePessoa", fila.getNomePessoa(),
                        "sala", sala.getNome(),
                        "status", fila.getStatus()
                ));
                jmsTemplate.send(topico, session -> session.createTextMessage(json));
            } catch (Exception e) {
                log.error("Erro ao republicar atendimento {} no painel {}: {}", fila.getId(), painelId, e.getMessage());
            }
        }
        log.info("Replay concluído para agencia={} painel={}: {} ativos encontrados", agenciaId, painelId, ativos.size());
    }
}
