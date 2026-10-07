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
---

## Store do broker sem PVC + Outbox transacional (ordem do flag `publicadoNoBroker`)

**Contexto:** O dimensionamento original previa um PVC de ~5 GiB para o journal de persistência do Artemis (cenário de 1.500 agências, 8h/dia, ~250.000 atendimentos/dia). O PVC não foi disponibilizado na infra. Alternativas avaliadas:

- **Oracle como store:** descartado. Não é histórico (store transiente, mensagens somem após o ack), pior custo/benefício (licença + overhead de UNDO/redo/archive), e é o antipadrão de "RDBMS como fila de alta rotatividade".
- **Postgres como store (JDBC store do Artemis):** melhor que Oracle se for por RDBMS (sem licença, já na stack), mas continua sendo o mesmo antipadrão (bloat/autovacuum numa tabela de insert+delete constante) e o JDBC store do Artemis é caminho menos maduro que o file journal.
- **Redis:** não compensa só para isto — seria trocar a mensageria inteira (Redis Streams/Pub-Sub), com durabilidade via AOF e custo em RAM.
- **Escolhido — Artemis com storage efêmero + outbox/replay existentes:** a fonte de verdade já é o Postgres da aplicação (tabela `fila_atendimento`); a mensagem JMS é só um ponteiro (`filaAtendimentoId`). O `OutboxPublisher` já reconstrói a queue de trabalho e o `ReplayListener` reconstrói os painéis. Sem banco novo, sem antipadrão, sem RAM extra.

**Problema a corrigir (dual-write):** hoje a triagem salva `publicadoNoBroker = true` de forma **otimista, antes** de confirmar a publicação no broker. Se o broker perder a mensagem (ex.: restart com storage efêmero), o registro fica `AGUARDANDO` com flag `true` e o `OutboxPublisher` **não** o republica (ele só busca `findByPublicadoNoBrokerFalseAndStatus("AGUARDANDO")`). Inverter a ordem (publicar na fila e só commitar no banco se publicou) **não** resolve: apenas troca a falha por uma pior (mensagem órfã no broker apontando para um registro que não existe no banco). A ordem correta é **banco primeiro** (recuperável) + **outbox transacional** (publica se e somente se commitou).

**Objetivo:** Ajustar para o padrão Transactional Outbox correto, de modo que uma mensagem seja considerada publicada **somente após** publicação confirmada — fechando a brecha do restart do broker sem 2PC.

**Passos:**
- [x] `TriagemService`: salva `publicadoNoBroker = false` dentro da transação (em vez de `true` otimista). **Implementado como Opção 1:** o publish inline continua como otimização de latência e marca `true` **somente após publicação confirmada** (dentro do mesmo `try`, após `publicarNaQueueAgencia` ter sucesso). O `catch` apenas loga — a flag permanece `false` para o outbox republicar. Se a transação sofrer rollback, o save da flag reverte junto (mesma transação). Build validado (`mvn compile`).
- [x] `OutboxPublisher`: confirmado como a **rede de segurança** que republica tudo que ficou com `publicadoNoBroker = false` e `status = AGUARDANDO`, marcando `true` após sucesso (polling com jitter a cada 3–7s). Nenhuma alteração necessária — já era o confirmador correto. O consumo é idempotente (`chamarProximo` descarta mensagem cujo status não é mais `AGUARDANDO`) e a race "consumo antes do commit da triagem" é coberta pelo `findById().orElseThrow()` no `AtendimentoService`.
- [ ] ~~Reconciliador de boot para capturar restart do broker~~ — **descartado como desnecessário.** Não há evento confiável de "restart do broker" para capturar, e o polling do outbox já cobre o caso naturalmente, desde que o flag esteja em `false` (garantido pelos passos acima).

**Dimensionamento (se ainda assim optarem por store durável externo em Postgres):** dimensionar pelo pico de backlog, não pelo throughput diário — ~10–15 GiB é folgado, com autovacuum agressivo na tabela de mensagens.
---

## Remover InternalController e código morto associado (api-painel)

**Contexto:** O endpoint `POST /api/internal/publicar` (`InternalController` na api-painel) **não tem nenhum consumidor** no sistema. Verificado:

- Não existe client HTTP na api-atendimento (nenhum `RestTemplate`/`WebClient`/`RestClient`/`Feign`/`HttpClient`) — a comunicação entre serviços é 100% via Artemis.
- A api-atendimento já publica os eventos de painel **diretamente** nos tópicos `agencia.*.painel.*` (`AtendimentoService.publicarNosPaineisDoServico` via `jmsTemplate.send`). O `InternalController` faria exatamente o mesmo (`PainelSseService.publicar` só faz `jmsTemplate.send` no mesmo tópico) — é um caminho HTTP→JMS redundante.
- `PainelSseService.publicar(update)` tem **um único chamador**: o próprio `InternalController`. Logo, controller + método + DTO formam um bloco isolado e removível por inteiro.
- A env `APP_API_PAINEL_URL` (setada para a api-atendimento no docker-compose, `http://api-painel:8081`) é **órfã**: nenhum código a lê. Resquício do design HTTP anterior, substituído pela mensageria via tópicos do Artemis.

**Risco de segurança (enquanto o endpoint existir):** `SecurityConfig` da api-painel libera `/api/internal/** → permitAll()`, ou seja, sem autenticação JWT do resource server. A única proteção é o header `X-Internal-Key` comparado com uma chave **estática hardcoded** (`chave-interna-fila-2024`) versionada no `application.yml` e no `docker-compose.yml`. Qualquer um com acesso de rede + a chave (que está no git) pode publicar em qualquer painel. Além disso, `apiKey.equals(key)` não é constant-time (comparação vulnerável a timing).

**Objetivo:** Remover o código morto e a superfície de ataque associada.

**Passos:** ✅ Concluído.
- [x] Removido `InternalController` (`api-painel/.../controller/InternalController.java`).
- [x] Removido `PainelSseService.publicar(PainelUpdateDTO)` (sem outro chamador). Removido também o campo injetado `jmsTemplate` (que só esse método usava) e o parâmetro do construtor correspondente.
- [x] Removido `PainelUpdateDTO` (`api-painel/.../dto/PainelUpdateDTO.java`).
- [x] Removida a regra `requestMatchers("/api/internal/**").permitAll()` do `SecurityConfig` da api-painel.
- [x] Removida a property `app.internal-api-key` do `application.yml` da api-painel e as env `APP_INTERNAL_API_KEY` do `docker-compose.yml` (nos dois serviços).
- [x] Removida a env órfã `APP_API_PAINEL_URL` do `docker-compose.yml`.
- [x] Build validado: `mvn compile` nas duas apps (api-painel e api-atendimento) e `docker compose config -q` — todos sem erros.
---

## Resiliência do Heartbeat dos painéis — análise e melhorias a decidir

**Contexto:** O liveness dos painéis é sinalizado pela queue `painel-heartbeat` (point-to-point), consumida pelo `HeartbeatListener` na api-atendimento, que grava `Painel.ultimoHeartbeat` (`now()` quando `online=true`, `null` quando `online=false`). Há três gatilhos de publicação:

- **Periódico:** `HeartbeatPublisher.publicarHeartbeats()` a cada **5 min** envia `online=true` para todos os painéis em `subscriptions`.
- **Ao conectar:** `PainelSseService.registrar(...)` publica `online=true` imediatamente.
- **Ao desconectar:** `PainelSseService.cleanup(...)` publica `online=false`.

Detecção de desconexão: o ping SSE a cada **15s** (`enviarPingSse`) força escrita no socket; se falha, dispara `cleanup` → `online=false`. A janela de "painel ativo" usada no `existePainelAtivoParaServico` é de **10 min**.

**Comportamento em falhas (levantado):**

- **Broker cai (restart efêmero):** publicação do heartbeat falha; os `try/catch` só logam `warn`. **Não há retry/outbox para heartbeat** — o sinal daquele ciclo é perdido e recomposto no próximo ciclo de 5 min. O `ultimoHeartbeat` envelhece até lá. As mensagens na queue somem no restart (inofensivo — sinal descartável).
- **api-painel cai (pod reiniciado):** no shutdown abrupto o `cleanup` não roda de forma confiável, então o `online=false` **pode não ser publicado**. O estado converge para "offline" por **envelhecimento** (recência), não por marcação — com atraso de até ~10 min. Na volta, os browsers reconectam e cada `registrar` republica `online=true` (ver item de thundering herd).
- **Browser do painel cai / perde rede:** ping SSE de 15s detecta na próxima escrita → `cleanup` → `online=false`. Caminho robusto, detecção em ~15s.
- **api-atendimento (listener) cai:** `HeartbeatListener` não consome enquanto fora. As mensagens são enviadas sem `DeliveryMode` explícito → default **PERSISTENT**; num broker com storage se acumulariam e seriam processadas atrasadas (podendo marcar "online" um painel já caído, corrigido no ciclo seguinte). Com broker efêmero, acumulam em memória e somem no restart.

**Avaliação:** o heartbeat é a parte mais tolerante a falhas (sinal de liveness, descartável, auto-recuperável). Perder um heartbeat não perde dado — só atrasa a convergência do estado online/offline. Não compromete a estratégia de broker efêmero.

**Pontos a decidir / melhorar:**
- [x] **Folga 5 min (publish) vs 10 min (janela de atividade):** documentado. Javadoc no `HeartbeatPublisher` + seção "Heartbeat dos painéis (liveness)" no README explicam a relação, o papel do publisher periódico (renovar `ultimo_heartbeat` de conexões longas) e a distinção/ligação com o ping SSE de 15s. _Resta decidir (opcional)_ se vira constante única/configurável para evitar que alguém reduza a janela sem perceber o efeito — baixa prioridade, já que o heartbeat é apenas informativo.
- [x] **Shutdown abrupto da api-painel não emite `online=false`:** _decidido não fazer._ A convergência por envelhecimento (~10 min) é aceitável — o heartbeat é apenas informativo (aviso "nenhum painel ativo", não bloqueia atendimento) e os browsers reconectam republicando `online=true` antes disso. Não compensa detecção ativa de pod morto.
- [x] **Dupla semântica de "offline":** _decidido não fazer._ A query trata `null` (offline explícito) e valor velho (envelhecimento) de forma equivalente; o `IS NOT NULL` é apenas redundante, não um bug. Não vale mexer na escrita do mecanismo de heartbeat por isso.
- [x] **Heartbeat agora é NON_PERSISTENT:** aplicado `setExplicitQosEnabled(true)` + `setDeliveryMode(NON_PERSISTENT)` nos dois pontos de publicação (`HeartbeatPublisher.publicarHeartbeats` periódico e `PainelSseService.publicarHeartbeat` conectar/desconectar). Sinal de liveness descartável não precisa ser persistido pelo broker — evita acúmulo/processamento atrasado num broker com storage. Build validado.
