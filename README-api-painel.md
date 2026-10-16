# api-painel

API Spring Boot **sem persistência** que conecta os painéis de chamada (frontend `app-painel`) aos eventos de atendimento. Expõe um endpoint SSE por painel, consome os eventos publicados pela `api-atendimento` no broker Artemis e os repassa ao browser em tempo real. Também mantém o liveness dos painéis via heartbeats.

> Visão geral do sistema completo (frontends, api-atendimento, Keycloak, Traefik, Artemis) está no [README raiz](README.md). Este documento foca no módulo `api-painel`.

---

## Stack

| Item | Versão / Tecnologia |
|------|---------------------|
| Java | 21 (virtual threads habilitados) |
| Spring Boot | 3.3.0 |
| Mensageria | ActiveMQ Artemis (JMS) via `spring-boot-starter-artemis` + `artemis-jakarta-client` |
| Segurança | OAuth2 Resource Server (JWT do Keycloak) |
| Transporte ao browser | Server-Sent Events (SSE) sobre Spring MVC |
| Porta HTTP | `8081` |
| Persistência | **nenhuma** (todo o estado é efêmero/em memória) |

---

## Responsabilidades

- Manter conexões SSE de longa duração, uma por painel conectado (`agenciaId:painelId`).
- Consumir os eventos de atualização de painel do broker e entregá-los ao browser correto.
- Solicitar **replay** dos atendimentos ativos quando um painel conecta/reconecta.
- Publicar **heartbeats** de liveness para a `api-atendimento`.

A `api-painel` não tem banco e não escreve estado de negócio. Ela é um adaptador entre o broker (JMS) e o browser (SSE).

---

## Arquitetura em camadas

Pacote raiz: `com.fila.apipainel`. Entrada: `ApiPainelApplication` (`@EnableScheduling`).

```
controller/   PainelController — endpoint SSE
service/      PainelSseService — conexões SSE, listener compartilhado, replay, ping
              HeartbeatPublisher — heartbeat periódico de liveness
config/        SecurityConfig — resource server JWT (token via header OU query param)
```

---

## Endpoint REST

| Método | Caminho | Descrição |
|--------|---------|-----------|
| GET | `/api/painel/sse/{agenciaId}/{painelId}` | Abre o stream SSE do painel. Produz `text/event-stream`. |

O endpoint valida que o claim JWT `agencia` corresponde ao `{agenciaId}` do path — caso contrário, responde `403`. Em sucesso, registra o emitter e devolve um `SseEmitter` de duração "infinita" (`Long.MAX_VALUE`).

Eventos SSE enviados ao browser:
- `painel-update` — corpo JSON do evento de atendimento (senha, nome, estação, status).
- Comentário `:ping` — a cada 15s, invisível para a aplicação (ver detecção de desconexão).

---

## Como funciona

### Conexão e listener compartilhado

Ao conectar (`PainelSseService.registrar`), o serviço cria um `SseEmitter` e o guarda num `ConcurrentHashMap` com chave `agenciaId:painelId`. O consumo do broker **não** cria um container por painel: há **um único** `DefaultMessageListenerContainer` (criado no `@PostConstruct`) assinando o wildcard de tópico `agencia.*.painel.*`.

Cada mensagem recebida é roteada pela chave `agenciaId:painelId` lida dos **headers JMS** (sem desserializar o corpo) para o emitter correto. Se não houver emitter local para aquela chave, a mensagem é ignorada. Isso desacopla o número de conexões SSE do custo no broker — conexões/subscriptions deixam de crescer com a quantidade de painéis.

**Multi-instância:** com wildcard + pub/sub, cada réplica recebe todos os eventos e descarta o que não tem emitter local; cada conexão permanece na réplica que a atendeu.

### Tópicos concretos no envio × uma subscription wildcard no consumo

Vale esclarecer uma assimetria entre quem publica e quem consome:

- **No envio**, os produtores (`AtendimentoService.publicarNosPaineisDoServico` e o `ReplayListener`, ambos na api-atendimento) montam o nome do tópico de forma **concreta**, um por painel: `agencia.<id>.painel.<n>` (ex.: `agencia.agencia-01.painel.1`, `agencia.agencia-01.painel.2`, `agencia.agencia-02.painel.1`). Não há wildcard na publicação.
- **No consumo**, há um **único** listener assinando o padrão `agencia.*.painel.*`. No Artemis, `*` é curinga de **um nível** no endereço hierárquico (separador `.`), então esse padrão casa com todos os tópicos concretos acima. Esse listener recebe as mensagens de todos eles.

Sobre "são criados vários tópicos": no Artemis cada tópico é um *address*. Com o wildcard, o broker cria **uma única** fila de assinatura vinculada ao address curinga, que recebe cópia de tudo publicado em qualquer address que case com o padrão. Ou seja, do ponto de vista do consumidor **não** são N subscriptions (uma por painel) — é **uma só**. Esse é o ganho de design do listener compartilhado: as subscriptions ao broker caem de ~N (número de painéis) para 1, em vez de um container JMS por painel conectado.

Como o nome do tópico concreto não é usado no roteamento, o `rotear(...)` identifica o destino pelos **headers JMS** `agenciaId` e `painelId` (anexados pelo produtor), monta a chave `agenciaId:painelId` e localiza o `SseEmitter`.

```
Produtores (api-atendimento)                Broker Artemis            api-painel
  send("agencia.01.painel.1", msg+headers) ─┐
  send("agencia.01.painel.2", msg+headers) ─┤  addresses concretos
  send("agencia.02.painel.1", msg+headers) ─┘        │
                                                     │ 1 subscription wildcard
                                                     ▼  agencia.*.painel.*
                                            ┌───────────────────────────┐
                                            │  listener compartilhado   │
                                            │  lê headers agenciaId/     │
                                            │  painelId → roteia p/ o    │
                                            │  SseEmitter correto        │
                                            └───────────────────────────┘
```

### Replay ao conectar

Logo após registrar o emitter, o serviço aguarda ~500ms (para a subscription do broker estabilizar) e publica `{ agenciaId, painelId }` na queue `replay-request`. A `api-atendimento` (`ReplayListener`) responde republicando os atendimentos ativos daquele painel no tópico `agencia.<id>.painel.<n>`, que chegam pelo listener compartilhado e aparecem no browser. Assim o painel recupera o estado após refresh, queda de rede ou reinício.

### Heartbeat e detecção de desconexão

- **Ao conectar / desconectar:** `PainelSseService` publica `online=true` / `online=false` na queue `painel-heartbeat`.
- **Periódico:** `HeartbeatPublisher` envia `online=true` a cada 5 min (após delay inicial de 10s) para todos os painéis conectados, **renovando** o `ultimoHeartbeat` de conexões longas (a janela de "ativo" na api-atendimento é de 10 min).
- **Ping SSE (15s):** como SSE é unidirecional, o Tomcat só percebe que o browser fechou ao tentar **escrever** no socket. O comentário `:ping` força essa escrita; se falha, dispara o `cleanup`, que remove o emitter e publica `online=false`.

Os heartbeats são publicados como **NON_PERSISTENT** — é sinal de liveness descartável, não precisa ser persistido pelo broker. Falhas de publicação apenas são logadas e se recompõem no ciclo seguinte; o heartbeat é informativo e não bloqueia o atendimento.

---

## Mensageria JMS (destinos)

| Destino | Tipo | Papel na api-painel |
|---------|------|---------------------|
| `agencia.*.painel.*` | tópico (wildcard) | **Consome** eventos de atendimento e roteia para o SSE do painel. |
| `replay-request` | queue | **Publica** o pedido de replay ao conectar/reconectar. |
| `painel-heartbeat` | queue | **Publica** o liveness (online/offline, periódico), NON_PERSISTENT. |

O consumo usa o container compartilhado; as publicações usam `JmsTemplate` pontuais (`pubSubDomain=false` para as filas).

---

## Segurança

`SecurityConfig` configura um **OAuth2 Resource Server** validando JWTs do Keycloak (realm `fila-atendimento`), com sessões `STATELESS` e CSRF desabilitado. Todas as rotas exigem autenticação.

Detalhe importante do SSE: o `EventSource` nativo do browser **não** suporta headers customizados, então o `DefaultBearerTokenResolver` é configurado com `setAllowUriQueryParameter(true)` — o token pode vir no header `Authorization` **ou** no query param `access_token`. Além do JWT válido, o controller exige que o claim `agencia` do token bata com a agência da URL.

> Nota: o token é validado apenas na **abertura** do stream; ele não é revalidado enquanto a conexão fica aberta. Quando o token expira e o `EventSource` do browser tenta reconectar, a reabertura falha com `401` — por isso a renovação proativa do token (antes de expirar, recriando o `EventSource`) é responsabilidade do front-end (`app-painel`).

---

## Configuração (`application.yml`)

| Chave | Padrão | Descrição |
|-------|--------|-----------|
| `server.port` | `8081` | Porta HTTP. |
| `spring.threads.virtual.enabled` | `true` | Virtual threads (Java 21): conexões SSE ociosas não prendem platform threads do Tomcat. |
| `spring.artemis.broker-url` | `tcp://localhost:61616` | Broker Artemis. |
| `spring.jms.pub-sub-domain` | `true` | Domínio pub/sub padrão (tópicos). |
| `spring.security.oauth2...issuer-uri` | `http://localhost:8080/realms/fila-atendimento` | Emissor dos JWT. |

As configurações de `logging` reduzem o ruído de desconexões SSE (broken pipe) do Tomcat/Coyote, esperadas quando browsers fecham a conexão.

Dependências externas em runtime: broker Artemis e Keycloak para validação dos JWT. **Não** depende de banco de dados.

---

## Build e execução

```bash
# Build do jar (a partir deste diretório)
mvn clean package

# Executar localmente (requer Artemis e Keycloak no ar)
mvn spring-boot:run
# ou
java -jar target/api-painel-1.0.0.jar
```

Via Docker (multi-stage; build com Maven/Temurin 21, runtime em `eclipse-temurin:21-jre-alpine`, expõe `8081`):

```bash
docker build -t api-painel .
```

Para subir o sistema completo (todos os módulos, broker e Keycloak), use o `docker compose` descrito no [README raiz](README.md).
