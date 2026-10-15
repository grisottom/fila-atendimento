package com.fila.apipainel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gerencia as conexões SSE dos painéis e o consumo dos eventos do broker.
 *
 * <p><b>Listener compartilhado:</b> em vez de um {@link DefaultMessageListenerContainer}
 * por painel conectado, há <b>um único</b> container assinando o wildcard de tópico
 * {@code agencia.*.painel.*}. Cada mensagem é roteada, pela chave {@code agenciaId:painelId}
 * lida dos <b>headers JMS</b>, para o {@link SseEmitter} correto no mapa {@link #emitters}.
 * Isso desacopla o consumo JMS do número de conexões SSE — o custo no broker
 * (conexões/subscriptions) deixa de crescer com a quantidade de painéis.
 *
 * <p><b>Multi-instância:</b> com wildcard + pub/sub, cada réplica recebe todos os
 * eventos e descarta (retorna) o que não tem emitter local. O comportamento "cada
 * conexão fica na réplica que a atendeu" se mantém.
 */
@Service
public class PainelSseService {

    private static final Logger log = LoggerFactory.getLogger(PainelSseService.class);

    /** Wildcard de tópico do Artemis: qualquer agência, qualquer painel. */
    private static final String TOPICO_WILDCARD = "agencia.*.painel.*";

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    /** Conexões SSE ativas nesta instância, desacopladas do consumo JMS. */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** Único container compartilhado que escuta o wildcard e roteia internamente. */
    private DefaultMessageListenerContainer sharedContainer;

    public PainelSseService(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    public Set<String> getPaineisConectados() {
        return emitters.keySet();
    }

    /**
     * Inicia o único consumidor compartilhado do broker, no startup (não por painel).
     */
    @PostConstruct
    void iniciarListenerCompartilhado() {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName(TOPICO_WILDCARD);
        container.setPubSubDomain(true);          // tópico (pub/sub)
        container.setSubscriptionDurable(false);  // assinatura temporária
        container.setMessageListener((MessageListener) this::rotear);
        container.afterPropertiesSet();
        container.start();
        this.sharedContainer = container;
        log.info("Listener compartilhado iniciado no wildcard {}", TOPICO_WILDCARD);
    }

    @PreDestroy
    void pararListenerCompartilhado() {
        if (sharedContainer != null) {
            try {
                sharedContainer.stop();
                sharedContainer.destroy();
            } catch (Exception e) {
                log.warn("Erro ao parar listener compartilhado: {}", e.getMessage());
            }
        }
    }

    /**
     * Roteia uma mensagem do wildcard para o emitter do painel de destino. A chave
     * {@code agenciaId:painelId} vem dos headers JMS (validados), sem desserializar
     * o corpo.
     */
    private void rotear(Message message) {
        String chave;
        try {
            String agenciaId = message.getStringProperty("agenciaId");
            int painelId = message.getIntProperty("painelId");
            chave = agenciaId + ":" + painelId;
        } catch (Exception e) {
            log.error("Erro ao ler headers de roteamento do evento de painel: {}", e.getMessage());
            return;
        }

        SseEmitter emitter = emitters.get(chave);
        if (emitter == null) return; // nenhum painel desta chave conectado a esta instância

        try {
            String body = ((TextMessage) message).getText();
            emitter.send(SseEmitter.event().name("painel-update").data(body));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.toLowerCase().contains("broken pipe") || msg.contains("already completed"))) {
                log.warn("Cliente desconectou ({}) para {}", msg, chave);
            } else {
                log.error("Erro ao enviar SSE para {}: {}", chave, msg);
            }
            cleanup(chave);
        }
    }

    public SseEmitter registrar(String agenciaId, Integer painelId) {
        String chave = agenciaId + ":" + painelId;

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(chave, emitter);

        // Callbacks para limpar a conexão SSE quando o cliente sumir.
        emitter.onCompletion(() -> cleanup(chave));
        emitter.onTimeout(() -> cleanup(chave));
        emitter.onError(e -> cleanup(chave));

        // Solicita replay com delay. O container compartilhado já está no ar desde o
        // startup; o delay cobre o timing da subscription do broker e evita corrida
        // com o registro do emitter neste mapa.
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            replayRequest(agenciaId, painelId);
        }).start();

        // Notifica imediatamente que o painel está online.
        publicarHeartbeat(chave, true);

        log.info("Painel conectado via SSE: {}", chave);

        return emitter;
    }

    /**
     * Ping SSE: envia um comentário vazio a cada 15s para cada painel conectado.
     *
     * O SSE é unidirecional (servidor → cliente). O Tomcat só detecta que o browser
     * fechou quando tenta ESCREVER no socket e recebe "Broken pipe". Sem escrita
     * periódica, uma conexão fechada pelo cliente fica "pendurada" em memória
     * indefinidamente (o servidor não sabe que o client desconectou).
     *
     * O comentário SSE (":ping") não gera evento no EventSource do browser — é
     * invisível para a aplicação. Serve apenas para forçar uma tentativa de escrita
     * no socket, permitindo ao Tomcat detectar a desconexão e acionar onError/onCompletion,
     * que por sua vez executa o cleanup e atualiza o status do painel para Offline
     * através da fila painel-heartbeat (mensagem do Painel -> Atendimento).
     */
    @Scheduled(fixedDelay = 15_000)
    public void enviarPingSse() {
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                // Escrita falhou → cliente desconectou → executa cleanup diretamente
                log.debug("Ping falhou para {} (cliente desconectou): {}", entry.getKey(), e.getMessage());
                cleanup(entry.getKey());
            }
        }
    }

    private void cleanup(String chave) {
        SseEmitter emitter = emitters.remove(chave);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // emitter já pode ter completado por conta própria
            }
            // Notifica imediatamente que o painel está offline
            publicarHeartbeat(chave, false);
            log.info("Painel desconectado: {}", chave);
        }
    }

    private void publicarHeartbeat(String chave, boolean online) {
        try {
            String[] partes = chave.split(":");
            String agenciaId = partes[0];
            int painelId = Integer.parseInt(partes[1]);
            String json = objectMapper.writeValueAsString(Map.of(
                    "agenciaId", agenciaId,
                    "painelId", painelId,
                    "online", online
            ));
            JmsTemplate filaTemplate = new JmsTemplate(connectionFactory);
            filaTemplate.setPubSubDomain(false);
            // Heartbeat é sinal de liveness descartável — não precisa ser persistido pelo broker.
            filaTemplate.setExplicitQosEnabled(true);
            filaTemplate.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
            filaTemplate.send("painel-heartbeat", session -> session.createTextMessage(json));
        } catch (Exception e) {
            log.warn("Erro ao publicar heartbeat offline para {}: {}", chave, e.getMessage());
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
}
