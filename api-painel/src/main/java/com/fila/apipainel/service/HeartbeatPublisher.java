package com.fila.apipainel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Publica periodicamente o liveness dos painéis conectados.
 *
 * <p>A cada 5 minutos envia {@code online=true} na queue {@code painel-heartbeat}
 * para cada painel atualmente conectado via SSE. A api-atendimento consome esses
 * sinais (HeartbeatListener) e atualiza {@code Painel.ultimoHeartbeat}, usado para
 * saber se um painel está ativo (janela de 10 min em {@code existePainelAtivoParaServico}).
 *
 * <p>Este publisher é complementar aos sinais pontuais emitidos pelo
 * {@code PainelSseService}: {@code online=true} ao conectar e {@code online=false}
 * ao desconectar. O papel do envio periódico é <b>renovar</b> o {@code ultimoHeartbeat}
 * de conexões de longa duração (painéis ficam abertos o dia todo), evitando que o
 * timestamp envelheça além da janela de 10 min e o painel seja considerado inativo
 * mesmo estando conectado.
 *
 * <p>É um sinal de liveness tolerante a falhas: se uma publicação falhar (broker
 * indisponível), apenas loga e se recompõe no próximo ciclo. O {@code ultimoHeartbeat}
 * tem efeito somente informativo (gera o aviso "nenhum painel ativo" ao chamar),
 * não bloqueia o atendimento.
 */
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
        // Heartbeat é sinal de liveness descartável — não precisa ser persistido pelo broker.
        filaTemplate.setExplicitQosEnabled(true);
        filaTemplate.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

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
