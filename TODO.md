# TODO

## Renovação proativa de token no SSE do Painel (front-end)

**Contexto:** A api-painel é um resource server stateless (JWT bearer). O JWT é validado apenas na abertura da conexão SSE (`GET /api/painel/sse/{agenciaId}/{painelId}`). Enquanto o stream fica aberto, o token não é revalidado. Quando o token expira e o `EventSource` do browser tenta reconectar, a reabertura falha com 401 e o painel para de atualizar (o EventSource nativo fica em loop de retry sem conseguir renovar o token sozinho).

**Objetivo:** Renovar o token proativamente no front-end (app-painel), antes de expirar, e recriar o `EventSource` com o token novo — em vez de esperar o 401.

**Passos previstos:**
- [ ] Ler o `exp` do JWT no front-end e agendar a renovação antes da expiração (ex.: renovar a ~80% do tempo de vida do token).
- [ ] Obter novo token (refresh token / fluxo do Keycloak em `http://localhost:8080/realms/fila-atendimento`).
- [ ] Fechar o `EventSource` atual e recriar com o `access_token` novo no query param.
- [ ] Tratar o caso de falha na renovação (redirecionar para login).

**Opcional (defesa server-side):** avaliar encerrar o emitter no `PainelSseService` quando o `exp` do JWT já tiver passado (guardar `exp` na `PainelSubscription` e checar no ciclo de ping de 15s).

---

## Refatorar consumo do Artemis na api-painel: listener compartilhado (redução de infra)

> Design detalhado: [docs/refatoracao-api-painel-sse.md](docs/refatoracao-api-painel-sse.md)

**Contexto:** Hoje o `PainelSseService.registrar(...)` cria **um `DefaultMessageListenerContainer` por painel conectado**. Cada container tem thread(s) de escuta e conexão/sessão JMS dedicadas. Com ~1.700 painéis simultâneos, são ~1.700 containers + ~1.700 threads só para escutar o broker — é o principal fator que força mais réplicas e mais memória na api-painel.

**Objetivo:** Trocar o modelo "1 container por painel" por **poucos consumidores compartilhados usando wildcard de tópico do Artemis** e rotear a mensagem internamente para o `SseEmitter` correto.

**Abordagem:**
- [ ] Usar wildcard de endereço do Artemis (ex.: `agencia.#` ou `agencia.*.painel.*`) num único (ou poucos) listener(s) compartilhado(s), em vez de um container por painel.
- [ ] Extrair o destino real da mensagem (ou de um header) para descobrir a chave `agenciaId:painelId` e localizar o `SseEmitter` no `Map` de subscriptions.
- [ ] Manter o `Map<String, SseEmitter>` de conexões SSE ativas, mas **desacoplado** do container JMS (que passa a ser compartilhado, não por painel).
- [ ] Ajustar o `cleanup` para remover apenas o emitter do painel, sem parar/destruir container (o container agora é global).
- [ ] Revalidar o mecanismo de replay: o replay-request continua igual, mas o evento republicado chega pelo listener compartilhado.

**Ganho esperado:** derruba drasticamente threads e conexões JMS ao broker; api-painel cabe em menos réplicas e menos memória; Artemis lida com muito menos conexões/subscriptions.

## Avaliar WebFlux/SSE reativo na api-painel (conexões de saída)

> Design detalhado: [docs/refatoracao-api-painel-sse.md](docs/refatoracao-api-painel-sse.md)

**Contexto:** A api-painel roda sobre Tomcat (servlet/MVC), modelo **thread-por-conexão**. Cada conexão SSE aberta (~1.700) segura uma thread do pool do Tomcat mesmo ficando ociosa entre eventos. SSE é justamente isso: conexões de longa duração, quietas a maior parte do tempo.

**Objetivo:** Avaliar migrar as conexões SSE de saída para **Spring WebFlux** (Netty + event loop), onde milhares de conexões ociosas são multiplexadas em poucas threads, reduzindo memória e contagem de threads.

**Passos:**
- [ ] Avaliar impacto (WebFlux muda o stack; a api-painel é pequena, então o custo de migração é contido).
- [ ] Expor o endpoint SSE como `Flux<ServerSentEvent<...>>` em vez de `SseEmitter`.
- [ ] Combinar com o listener compartilhado do item anterior (a mensagem do broker vira um sink/`Sinks.Many` que o `Flux` de cada painel consome).
- [ ] Teste de carga comparativo (Tomcat atual vs. WebFlux) com `teste/03-teste-painel-sse.sh` para medir memória/threads reais.

---

## Pior caso: reconexão em massa (thundering herd) + Jitter na reconexão

**Cenário:** Se todos os pods caírem no meio do dia, no retorno os ~1.700 painéis reconectam quase ao mesmo tempo. Cada reconexão dispara um `replay-request`, concentrando numa rajada de poucos segundos o que normalmente estaria diluído ao longo das 8h. A rajada bate em três pontos: Postgres (consultas do `ReplayListener`), api-atendimento (drenagem da fila `replay-request`) e Artemis/api-painel (republicação de milhares de eventos — com o wildcard, cada evento vai para todas as réplicas).

**Proteção que já existe:** o `ReplayListener` consome a fila `replay-request` por um único container, o que serializa/enfileira a rajada naturalmente (a fila JMS absorve o pico). O custo é latência: os últimos painéis demoram um pouco mais para popular — degradação graciosa, aceitável.

**Mitigação principal — Jitter na reconexão (front-end app-painel):**
- [ ] Ao reconectar o `EventSource` após queda, aguardar um atraso aleatório (ex.: 0-10s) antes de reabrir, para espalhar a rajada no tempo em vez de todos reconectarem no mesmo instante. É a mitigação de maior impacto pelo menor esforço, e ataca a raiz do problema.

**Mitigações complementares (infra/back-end):**
- [ ] Limitar a concorrência do `ReplayListener` (evitar disparar queries demais em paralelo no Postgres).
- [ ] Dimensionar o pool de conexões do Postgres (HikariCP) na api-atendimento para o pico de replays.
- [ ] Ajustar readiness/liveness e PodDisruptionBudget para o sistema não voltar "frio" e sobrecarregado.
- [ ] (Opcional) Coalescing/idempotência de replay-requests duplicados do mesmo painel.

**Dado a confirmar:** número de atendimentos simultâneos em CHAMANDO/EM_ATENDIMENTO no pico — define o tamanho real da rajada de replay e o dimensionamento do Postgres/pool.
