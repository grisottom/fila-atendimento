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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
    private final RestClient restClient;
    private final String internalApiKey;

    public ReplayListener(FilaAtendimentoRepository filaRepository,
                          SalaRepository salaRepository,
                          ConnectionFactory connectionFactory,
                          ObjectMapper objectMapper,
                          @Value("${app.api-painel-url}") String apiPainelUrl,
                          @Value("${app.internal-api-key}") String internalApiKey) {
        this.filaRepository = filaRepository;
        this.salaRepository = salaRepository;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.internalApiKey = internalApiKey;
        this.restClient = RestClient.builder().baseUrl(apiPainelUrl).build();
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
                restClient.post()
                        .uri("/api/internal/publicar")
                        .header("X-Internal-Key", internalApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "agenciaId", agenciaId,
                                "painelId", painelId,
                                "senha", fila.getSenha(),
                                "nomePessoa", fila.getNomePessoa(),
                                "sala", sala.getNome(),
                                "status", fila.getStatus()
                        ))
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.error("Erro ao republicar atendimento {} no painel {}: {}", fila.getId(), painelId, e.getMessage());
            }
        }
        log.info("Replay concluído para agencia={} painel={}: {} ativos encontrados", agenciaId, painelId, ativos.size());
    }
}
