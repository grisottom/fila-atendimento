package com.fila.apiatendimento.config;

import jakarta.jms.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.core.JmsTemplate;

@Configuration
public class JmsConfig {

    /**
     * JmsTemplate padrão para publicação em tópicos (pub/sub).
     * Usado para notificações de painel e replay.
     */
    @Primary
    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(true);
        return template;
    }

    /**
     * JmsTemplate para operações em filas (point-to-point).
     * Usa sessão transacionada: o consumo da mensagem é confirmado automaticamente
     * ao final da chamada receiveSelected. A consistência com o banco é garantida
     * pela idempotência (checagem de status AGUARDANDO antes de processar).
     */
    @Bean("jmsQueueTemplate")
    public JmsTemplate jmsQueueTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(false);
        template.setSessionTransacted(true);
        template.setReceiveTimeout(2000); // 2 segundos — retorna null se não houver mensagem
        return template;
    }
}
