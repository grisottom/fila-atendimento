# Refatoração da api-painel — Listener JMS compartilhado + SSE reativo

Documento de design para reduzir o footprint de infraestrutura da `api-painel`, que hoje é
dimensionada pelo número de conexões SSE simultâneas (~1.700 painéis no cenário atual:
1500 agências, 90% com 1 painel, 7% com 2, 3% com 3).

> Este documento é referenciado pelo `TODO.md` na raiz do projeto.

---

## 1. Problema atual

O `PainelSseService.registrar(...)` cria, para **cada painel conectado**, um
`DefaultMessageListenerContainer` dedicado escutando o tópico `agencia.<id>.painel.<n>`:

```java
DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
container.setConnectionFactory(connectionFactory);
container.setDestinationName(topico);        // um tópico por painel
container.setPubSubDomain(true);
container.setMessageListener(msg -> { ... emitter.send(...) ... });
container.start();
```

Consequências com ~1.700 painéis:

- ~1.700 containers JMS, cada um com thread(s) de escuta e conexão/sessão com o Artemis.
- ~1.700 threads do Tomcat presas a conexões SSE que ficam **ociosas quase o tempo todo**
  (um evento eventual + ping de 15s).
- A `api-painel` precisa de mais réplicas e mais memória do que a carga real de dados exigiria.
  O throughput de eventos é baixo (~26 eventos/s médio, ~100/s no pico); o custo está nas
  **conexões**, não nos dados.

---

## 2. Duas mudanças independentes e complementares

| # | Mudança | Resolve o lado | Efeito |
|---|---------|----------------|--------|
| A | Listener JMS compartilhado com wildcard | broker → api-painel | Deixa de ser 1 consumidor JMS por painel |
| B | SSE reativo (WebFlux/Netty) | api-painel → browser | Deixa de ser 1 thread por conexão SSE |

Podem ser feitas separadamente, mas juntas se encaixam: o listener compartilhado empurra
o evento para um `Sinks.Many`, e cada painel tem um `Flux` que consome só o que lhe interessa.

---

## 3. Mudança A — Listener compartilhado com wildcard

### Ideia

Em vez de um container por painel, usar **um (ou poucos) consumidor(es) compartilhado(s)**
assinando um endereço com wildcard do Artemis e roteando internamente para o `SseEmitter`
correto, a partir de um `Map<chave, emitter>` mantido em memória.

Wildcards do Artemis (sintaxe hierárquica com `.` como separador):
- `agencia.#`  → tudo abaixo de `agencia`
- `agencia.*.painel.*` → qualquer agência, qualquer painel (mais específico, recomendado)

### Como descobrir o painel de destino (roteamento)

O consumidor compartilhado recebe mensagens de vários painéis. Precisa saber a qual
`SseEmitter` entregar. Três formas, em ordem de preferência:

1. **Header JMS (mais limpo)** — requer pequena alteração no publicador (ver seção 4).
   ```java
   String agenciaId = msg.getStringProperty("agenciaId");
   int painelId = msg.getIntProperty("painelId");
   ```
2. **Destino da mensagem** — sem alterar o publicador; parse do nome do tópico.
   ```java
   Destination dest = msg.getJMSDestination();          // agencia.01.painel.2
   // extrair agenciaId/painelId por parsing do nome
   ```
3. **Corpo JSON** — sem alterar o publicador, mas obriga desserializar para rotear.
   O corpo atual já contém `agenciaId` e `painelId`.

### Esboço do serviço refatorado

```java
@Service
public class PainelSseService {

    // conexões SSE ativas, desacopladas do JMS
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // UM container compartilhado, criado no startup (não por painel)
    @PostConstruct
    void iniciarListenerCompartilhado() {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName("agencia.*.painel.*");   // wildcard
        container.setPubSubDomain(true);
        container.setMessageListener((MessageListener) msg -> rotear(msg));
        container.afterPropertiesSet();
        container.start();
    }

    private void rotear(Message msg) throws Exception {
        String chave = extrairChave(msg);          // header, destino ou corpo
        SseEmitter emitter = emitters.get(chave);
        if (emitter == null) return;                // nenhum painel conectado a esta instância
        emitter.send(SseEmitter.event().name("painel-update")
                .data(((TextMessage) msg).getText()));
    }

    public SseEmitter registrar(String agenciaId, Integer painelId) {
        String chave = agenciaId + ":" + painelId;
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(chave, emitter);
        emitter.onCompletion(() -> emitters.remove(chave));
        emitter.onTimeout(() -> emitters.remove(chave));
        emitter.onError(e -> emitters.remove(chave));
        // replay-request continua igual
        return emitter;
    }
}
```

### Ponto de atenção multi-instância (pub/sub)

Com wildcard e **pub/sub**, cada réplica da `api-painel` recebe TODOS os eventos de TODOS
os painéis (o tópico é broadcast). Cada réplica descarta o que não tem emitter local
(`emitter == null` → return). Isso funciona, mas gera tráfego de broadcast entre broker e
todas as réplicas. Alternativas a avaliar no teste de carga:

- Aceitar o broadcast (simples; tráfego de ~100 msg/s no pico é baixo, cada réplica só
  ignora o que não é seu).
- Usar assinaturas mais direcionadas por instância se o broadcast se mostrar custoso.

O comportamento de "cada conexão fica na réplica que a atendeu" **se mantém** — o que muda
é apenas COMO o consumo do broker é feito (compartilhado vs. por painel).

### Impacto no replay

O mecanismo de replay não muda: a `api-painel` continua publicando `{agenciaId, painelId}`
na fila `replay-request`, e o `ReplayListener` da `api-atendimento` republica no tópico do
painel. A diferença é que a mensagem republicada chega pelo **listener compartilhado** em
vez de um container dedicado.

---

## 4. A api-atendimento precisa mudar?

**Não é obrigatório.** O publicador atual (`AtendimentoService` e `ReplayListener`) já:

- Publica em tópico com nome estruturado: `agencia.<id>.painel.<n>`.
- Inclui `agenciaId` e `painelId` no corpo JSON.

Portanto, o roteamento pode ser feito **sem tocar na api-atendimento**, usando o destino
da mensagem (opção 2) ou os campos do corpo (opção 3).

**Otimização opcional (retrocompatível):** adicionar headers JMS no publicador para permitir
roteamento sem parse/desserialização:

```java
jmsTemplate.send(topico, session -> {
    TextMessage m = session.createTextMessage(json);
    m.setStringProperty("agenciaId", fila.getAgenciaId());
    m.setIntProperty("painelId", painel.getNumeroPainel());
    return m;
});
```

Adicionar propriedades não quebra consumidores existentes. **Recomendação:** começar sem
alterar a api-atendimento (roteando por destino/corpo) e adicionar headers só se o teste
de carga indicar que o parse/desserialização vale a pena otimizar.

---

## 5. Mudança B — SSE reativo (WebFlux)

### Motivação

O modelo servlet/Tomcat é **thread-por-conexão**: ~1.700 threads presas a conexões ociosas.
WebFlux (Netty) usa **event loop**: poucas threads (~1 por núcleo) multiplexam milhares de
conexões, usando thread só quando há trabalho real. SSE é o caso ideal, pois as conexões
ficam quietas entre eventos.

### Esboço

```java
@GetMapping(value = "/sse/{agenciaId}/{painelId}", produces = TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> conectar(@PathVariable String agenciaId,
                                               @PathVariable Integer painelId) {
    Sinks.Many<String> sink = registrarSink(agenciaId + ":" + painelId);
    return sink.asFlux()
        .map(body -> ServerSentEvent.<String>builder(body).event("painel-update").build());
}
```

O listener compartilhado (mudança A) faz `sink.tryEmitNext(body)` para a chave do painel.

### Ressalvas

- WebFlux muda o modelo de programação (não-bloqueante, `Flux`/`Mono`). Custo de migração
  contido aqui porque a api-painel **não tem persistência** (sem JDBC bloqueante).
- O ping de heartbeat (hoje `@Scheduled` de 15s) vira um `Flux.interval(...)` mesclado ao
  stream, ou um comentário SSE periódico por conexão.
- **Medir antes de decidir:** teste comparativo Tomcat atual vs. WebFlux com
  `teste/03-teste-painel-sse.sh`, observando memória e contagem de threads.

### Alternativa mais leve ao WebFlux: Virtual Threads (Java 21+)

O problema que a mudança B ataca é "1 platform thread do Tomcat presa por conexão SSE
ociosa". **Virtual threads resolvem exatamente isso sem reescrever para o modelo reativo.**
No Spring Boot 3.2+ com Java 21, basta:

```properties
spring.threads.virtual.enabled=true
```

Com isso, cada conexão SSE ociosa passa a custar quase nada em threads (a virtual thread é
desmontada do carrier enquanto espera I/O), obtendo o mesmo ganho do WebFlux no lado
SSE — porém mantendo o código servlet/`SseEmitter` atual, sem migrar para `Flux`/`Mono`.

**Recomendação:** avaliar virtual threads **antes** do WebFlux. Mesmo ganho no lado das
conexões SSE, esforço de migração muito menor. WebFlux só se justifica se houver outra
razão para adotar o stack reativo.

> **IMPORTANTE — não substitui a mudança A.** Virtual threads barateiam *threads*, mas
> NÃO reduzem o número de conexões/sessões JMS nem de subscriptions no Artemis. O gargalo
> de "1 `DefaultMessageListenerContainer` por painel" (~1.700 conexões e subscriptions no
> broker) só é resolvido pela mudança A (listener compartilhado com wildcard). Trocar as
> threads do container por virtuais não muda o custo no broker.

Resumo de qual mudança resolve o quê:

| Gargalo | Virtual threads? | Solução |
|---|---|---|
| Conexões/sessões JMS ao broker (~1.700) | Não | Mudança A (listener compartilhado) |
| Subscriptions no Artemis (~1.700) | Não | Mudança A (listener compartilhado) |
| Platform threads em conexões SSE ociosas (~1.700) | **Sim** | Virtual threads OU WebFlux (mudança B) |

---

## 6. Ganho esperado

- **Threads:** de ~3.400 (1.700 listeners JMS + 1.700 threads SSE) para poucas dezenas.
- **Conexões ao broker:** de ~1.700 para poucas (consumidores compartilhados).
- **Réplicas da api-painel:** de 2-3 para 1-2 réplicas menores, com folga.

Todos os números acima são estimativas de design. Confirmar com teste de carga antes de
fixar `requests/limits` no Kubernetes.
