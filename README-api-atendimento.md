# api-atendimento

API Spring Boot que centraliza a lógica de negócio do sistema **Fila de Atendimento**: triagem (recepção), fila de senhas, ciclo de atendimento e publicação de eventos para os painéis de chamada. É o único módulo com persistência (Postgres) e a fonte de verdade do estado dos atendimentos.

> Visão geral do sistema completo (frontends, api-painel, Keycloak, Traefik, Artemis) está no [README raiz](README.md). Este documento foca no módulo `api-atendimento`.

---

## Stack

| Item | Versão / Tecnologia |
|------|---------------------|
| Java | 21 |
| Spring Boot | 3.3.0 |
| Persistência | Spring Data JPA + PostgreSQL |
| Mensageria | ActiveMQ Artemis (JMS) via `spring-boot-starter-artemis` |
| Segurança | OAuth2 Resource Server (JWT do Keycloak) |
| Porta HTTP | `8082` |

---

## Arquitetura em camadas

Pacote raiz: `com.fila.apiatendimento`. Entrada: `ApiAtendimentoApplication`.

```
controller/   REST — AdminController, AtendimentoController, TriagemController
service/      Regra de negócio — TriagemService, AtendimentoService,
              OutboxPublisher, DlqReprocessor
repository/   Spring Data JPA (FilaAtendimentoRepository, PainelServicoRepository, ...)
entity/       Entidades JPA mapeadas para o Postgres
dto/          Records de request/response
config/       SecurityConfig, JmsConfig, GlobalExceptionHandler, NegocioException
listener/     Consumidores JMS — HeartbeatListener, ReplayListener, DlqListener
```

Fluxo típico: o controller extrai usuário/agência do JWT e delega ao service; o service persiste via repositórios e publica/consome no Artemis via `JmsTemplate`; os listeners consomem mensagens do broker e chamam de volta services/repositórios.

---

## Modelo de dados (entidades)

| Entidade | Tabela | Observações |
|----------|--------|-------------|
| `Agencia` | `agencia` | PK `id` String (ex.: `agencia-01`). Tudo é escopado por agência. |
| `Painel` | `painel` | `numeroPainel`, `localizacao`, `ultimoHeartbeat` (liveness). |
| `Estacao` | `estacao` | `tipoEstacao` (MESA/GUICHE/SALA), `numeroEstacao`; `getNomeExibicao()` gera rótulos como "Guichê 3". |
| `Servico` | `servico` | PK `id` String; `permissaoExigida` (`basica`/`normal`/`especial`). |
| `PainelServico` | `paineis_servicos` | Associação N:N painel↔serviço. **Painel se associa ao serviço, não à estação.** |
| `Atendente` | `atendente` | `username`, `agenciaId`; `@OneToMany` de `PermissaoAtendente`. |
| `PermissaoAtendente` | `permissoes_atendente` | `permissao`; define quais serviços o atendente pode chamar. |
| `Pessoa` | `pessoa` | PK `cpf` (Long), `nome`. |
| `Agendamento` | `agendamento` | Agendamento do dia; é consumido (deletado) na recepção. |
| `FilaAtendimento` | `fila_atendimento` | Agregado central (ver abaixo). |

### FilaAtendimento — o agregado central

Campos de destaque:

- `triagemUuid` — UUID de **correlação** gerado na aplicação **antes** do insert. É esse valor (não a PK) que viaja na mensagem do broker, permitindo publicar e persistir numa única escrita e dar suporte à idempotência.
- `senha` — código de 5 caracteres (maiúsculas + números).
- `status` — `AGUARDANDO` → `CHAMANDO` → `EM_ATENDIMENTO` → `FINALIZADO`/`CANCELADO`; e `AUSENTE`; mais o terminal `FALHA_DLQ`.
- `horarioAgendado` (`null` = espontâneo), `horarioChegada`, `horarioChamada`, `horarioInicioAtendimento`, `horarioFimAtendimento`, `posicaoFila`.
- `publicadoNoBroker` — flag do outbox pattern.
- `tentativasDlq` — contador anti-loop **persistente** de reprocessamentos a partir da DLQ (o `JMSXDeliveryCount` do broker reseta a cada ciclo).
- `version` — lock otimista.

---

## Endpoints REST

Autorização (ver [SecurityConfig](#segurança)): `/api/admin/**` exige role `admin`; `/api/triagem/**` e `/api/atendimento/**` exigem `admin` ou `atendente`. O `username` vem do claim JWT `preferred_username`; a detecção de admin lê `realm_access.roles`.

### `/api/triagem`

| Método | Caminho | Descrição |
|--------|---------|-----------|
| POST | `/recepcionar` | Recepciona a pessoa (CPF + serviço), gera senha e insere na fila. Body `TriagemRequest`, retorna `TriagemResponse`. |
| PUT | `/atualizar/{id}` | Altera o serviço de um item (volta a `AGUARDANDO`). Body `{ "servicoId": "..." }`. |
| GET | `/agendamentos/{agenciaId}?page=&size=` | Agendamentos do dia (paginado). |
| GET | `/atendimentos/{agenciaId}` | Itens do dia em `AGUARDANDO`/`CANCELADO`/`AUSENTE`/`FALHA_DLQ`. |
| POST | `/republicar/{id}` | Rede de segurança: republica manualmente um item na fila do broker. |

### `/api/atendimento`

| Método | Caminho | Descrição |
|--------|---------|-----------|
| POST | `/chamar` | Chama o próximo da fila. Body `ChamarProximoRequest(estacaoId)`; agência e permissões derivadas da estação/JWT. |
| GET | `/ativo` | Chamada ativa do atendente (`CHAMANDO`/`EM_ATENDIMENTO`) ou `204`. |
| POST | `/ausentar/{id}` | Marca `AUSENTE` e move o item para o fim da fila. |
| POST | `/iniciar/{id}` | Transição para `EM_ATENDIMENTO`. |
| POST | `/finalizar/{id}` | Transição para `FINALIZADO`. |
| POST | `/cancelar/{id}` | Transição para `CANCELADO`. |
| GET | `/estacoes/{agenciaId}` | Lista estações. |
| GET | `/meus-servicos?agenciaId=` | Serviços que o atendente pode chamar. |
| GET | `/fila-disponivel?agenciaId=` | Fila de espera filtrada pelas permissões do atendente. |

### `/api/admin`

| Método | Caminho | Descrição |
|--------|---------|-----------|
| POST / GET | `/agencia` | Cria / lista agências. |
| POST / GET / DELETE | `/painel`, `/painel/{agenciaId}`, `/painel/{id}` | CRUD de painéis. |
| POST / GET / DELETE | `/estacao`, `/estacao/{agenciaId}`, `/estacao/{id}` | CRUD de estações. |
| GET / POST / PUT / DELETE | `/atendentes/...` | Gestão de atendentes e permissões. |
| GET | `/servicos` | Lista serviços. |
| GET / POST / DELETE | `/paineis-servicos/...` | Associação painel↔serviço. |

---

## Fluxos principais

### Recepção (`TriagemService.recepcionar`)

1. Resolve o nome da pessoa pelo CPF (fallback para o request).
2. Procura um `Agendamento` do dia (CPF + agência + serviço) para decidir **agendado vs espontâneo**.
3. Gera senha de 5 caracteres e a próxima `posicaoFila`.
4. Monta o `FilaAtendimento` (`AGUARDANDO`) e gera o `triagemUuid` **antes** do insert.
5. **Outbox transacional com uma única escrita**: tenta publicar inline na queue `agencia.<id>.fila`; em sucesso marca `publicadoNoBroker=true`, em falha marca `false` (o `OutboxPublisher` republica depois). Um único `save` grava o estado final.
6. Se havia agendamento correspondente, ele é deletado.

A prioridade JMS é **9 para agendados** e **4 para espontâneos** — agendados são consumidos primeiro.

### Chamar próximo (`AtendimentoService.chamarProximo`)

Transacional. Resumo:

1. Resolve a estação e a agência; se o atendente já tem item `CHAMANDO`, reexibe nos painéis e retorna (idempotência).
2. Consome a próxima mensagem da queue `agencia.<id>.fila` via `receiveSelected` com selector `permissao IN (...)` montado a partir das permissões do atendente.
3. Lê o `triagemUuid` da mensagem e busca a linha correspondente:
   - **Mensagem órfã** (sem linha): descarta sem lançar exceção para evitar uma *poison message* — uma mensagem que falha no processamento a cada tentativa e, se devolvida à fila em rollback, seria reentregue indefinidamente em loop (ver [seção da DLQ](#reprocessamento-da-dlq-dlqlistener--dlqreprocessor)) — e sinaliza indisponibilidade temporária.
   - **Reentrega** (status já não é `AGUARDANDO`): descarta a duplicata e devolve um aviso do estado real.
4. **Verificação de painel**: sem painel associado ao serviço → `NegocioException` (409); sem painel **ativo** (heartbeat < 10 min) com `exigir-painel-ativo=true` → 409; com `false`, segue com aviso.
5. Atualiza para `CHAMANDO` (estação, atendente, horário) e publica o evento nos painéis do serviço.

No `catch`, **não** há reset manual de outbox: com `sessionTransacted=true`, o rollback devolve a mensagem à fila automaticamente (redelivery).

### Ciclo de atendimento

`ausentar` → `AUSENTE` (vai ao fim da fila) · `iniciar` → `EM_ATENDIMENTO` · `finalizar` → `FINALIZADO` · `cancelar` → `CANCELADO`. Cada transição publica um evento nos painéis do serviço (`publicarNosPaineisDoServico`).

---

## Mensageria JMS (`JmsConfig`)

Dois `JmsTemplate`:

- `jmsTemplate` (`@Primary`, `pubSubDomain=true`) — publica em **tópicos** (eventos de painel, replay).
- `jmsQueueTemplate` (`pubSubDomain=false`, `sessionTransacted=true`, `receiveTimeout=1000ms`) — operações **point-to-point** na fila; a sessão transacionada liga o ack da mensagem ao resultado da transação (base da idempotência e do redelivery automático).

Os **consumos de fila** (heartbeat, replay, DLQ) não usam os templates acima: cada listener monta seu próprio `DefaultMessageListenerContainer`. A coluna "Mecanismo" abaixo indica, por destino, qual template publica (`jmsTemplate`/`jmsQueueTemplate`) e qual container consome.

| Destino | Tipo | Produtor → Consumidor | Mecanismo |
|---------|------|------------------------|-----------|
| `agencia.<id>.fila` | queue | Triagem/Outbox → `chamarProximo` | produz e consome via `jmsQueueTemplate` (`send` / `receiveSelected`) |
| `agencia.<id>.painel.<n>` | tópico | este serviço → api-painel | publica via `jmsTemplate` (`AtendimentoService`, `ReplayListener`) |
| `replay-request` | queue | api-painel → `ReplayListener` | consome via container do `ReplayListener` |
| `painel-heartbeat` | queue | api-painel → `HeartbeatListener` | consome via container do `HeartbeatListener` |
| `DLQ` | queue | broker → `DlqListener` | consome via container do `DlqListener` |

Listeners (cada um monta seu `DefaultMessageListenerContainer` no `@PostConstruct`):

- **HeartbeatListener** — grava `Painel.ultimoHeartbeat` (`now()` em online, `null` em offline). Falhas apenas logadas (sinal informativo).
- **ReplayListener** — ao reconectar um painel, republica os atendimentos ativos (`CHAMANDO`/`EM_ATENDIMENTO`) daquele painel no tópico correspondente.
- **DlqListener** (`sessionTransacted=true`) — delega ao `DlqReprocessor`; retornar normalmente remove da DLQ, lançar exceção mantém.

## Resiliência: outbox, redelivery e DLQ

A fonte de verdade é a tabela `fila_atendimento`; a mensagem JMS é apenas um ponteiro (`triagemUuid`). Três mecanismos garantem que nenhum item fique preso quando o broker ou o banco falham.

### Outbox pattern (`OutboxPublisher`)

A coluna `publicado_no_broker` funciona como outbox: a triagem grava a linha com `publicadoNoBroker=false` e só marca `true` **após** a publicação confirmada (ver [Recepção](#recepção-triagemservicerecepcionar)). Se o publish falhar, a flag fica `false` e o `OutboxPublisher` republica.

Como funciona o publisher:
- Agenda-se a si mesmo via `TaskScheduler` com **jitter de 3–7s** (em vez de `fixedDelay`), evitando que múltiplas réplicas disparem no mesmo instante.
- A cada ciclo, `publicarPendentes()` busca `findByPublicadoNoBrokerFalseAndStatus("AGUARDANDO")`, republica cada item na queue da agência e, recarregando a linha para respeitar a `@Version`, marca `publicadoNoBroker=true`.
- Falha ao publicar um item apenas loga; o próximo ciclo tenta de novo.

Um índice parcial mantém o polling barato (não faz full scan), pois os pendentes são uma fração mínima da tabela:

```sql
CREATE INDEX idx_fila_outbox_pendente
    ON fila_atendimento(id)
    WHERE status = 'AGUARDANDO' AND publicado_no_broker = FALSE;
```

Existe ainda `resetarPublicacao(filaId)`, `@Transactional(propagation = REQUIRES_NEW)`, que apenas volta a flag para `false` numa transação independente. **Não é usado no caminho atual do `chamarProximo`** (ver abaixo); permanece como utilitário para forçar republicação pelo outbox sem depender da transação externa.

### Redelivery automático no `chamarProximo`

O `jmsQueueTemplate` usa `sessionTransacted=true`, então o consumo da mensagem fica atrelado à transação. Se o processamento falhar e a transação sofrer rollback, a sessão JMS também faz rollback e a **mensagem volta sozinha para a queue** (redelivery) — por isso o `catch` **não** faz reset manual de outbox (seria redundante e geraria mensagem duplicada). O item permanece `AGUARDANDO` e é reentregue até o limite de tentativas do broker; esgotado o limite, o broker move a mensagem para a `DLQ`.

### Reprocessamento da DLQ (`DlqListener` + `DlqReprocessor`)

Uma **poison message** ("mensagem-veneno") é uma mensagem que falha no processamento a cada entrega: como o consumo é transacionado, cada falha faz rollback e a mensagem volta à fila, só para falhar de novo no próximo consumo — um loop. Para quebrá-lo, o broker conta as entregas e, ao esgotar o limite de tentativas, move a mensagem para a **DLQ** (*Dead-Letter Queue*), uma fila à parte para mensagens que não puderam ser processadas.

No fluxo "chamar próximo", quando uma mensagem acaba na `DLQ`, o `DlqListener` (container `sessionTransacted=true`) a consome e delega a decisão ao `DlqReprocessor`:

1. Lê o `triagemUuid` da mensagem. Se for ilegível, ausente ou inválido, confirma o consumo (ACK) e descarta — reprocessar não ajudaria.
2. `DlqReprocessor.reprocessar(uuid, maxReprocessos)`, numa única transação:
   - **Idempotência:** se a linha não existe ou já saiu de `AGUARDANDO`, descarta.
   - Incrementa o contador **persistente** `tentativas_dlq` no item.
   - Se `tentativas_dlq > maxReprocessos` → marca status **`FALHA_DLQ`** (terminal; quebra o loop DLQ→fila→DLQ e exige intervenção manual via `POST /republicar/{id}`).
   - Caso contrário → volta `publicadoNoBroker=false` para o `OutboxPublisher` republicar no próximo ciclo.

O ack segue o resultado da transação: retorno normal **comita** (remove da DLQ); exceção (ex.: banco fora) faz **rollback** e a mensagem **permanece na DLQ** para nova tentativa.

> **Por que o contador vive no item, e não no broker?** O `JMSXDeliveryCount` do Artemis conta reentregas *dentro* de uma mesma permanência na fila; ele **reseta a cada ciclo de DLQ** — comprovado empiricamente: a mensagem reinjetada (DLQ → `resetarPublicacao` → outbox) volta sempre com `JMSXDeliveryCount = 1`. Um teto anti-loop baseado nele nunca dispararia (loop infinito fila → DLQ → fila). Por isso o contador precisa persistir no item: o `tentativas_dlq` em `fila_atendimento` é o único estado que sobrevive ao ciclo de DLQ. O teto vem de `atendimento.dlq-max-reprocessos` (ver [Configuração](#configuração-applicationyml)).

### As quatro camadas de garantia

Os mecanismos acima formam uma defesa em camadas, cada uma cobrindo a falha da anterior:

| # | Camada | Cobre | Fonte de verdade |
|---|--------|-------|------------------|
| 1 | Rollback da sessão JMS (`sessionTransacted=true`) | Falha/rollback na transação do "chamar próximo" | Mensagem no broker |
| 2 | Redelivery + DLQ do broker | Falhas repetidas de processamento (poison message), com teto de tentativas | Mensagem no broker |
| 3 | `DlqReprocessor` (transacionado) | Reprocessar quando a causa se resolve (ex.: banco volta); parar o loop em `FALHA_DLQ` | DLQ + banco |
| 4 | `OutboxPublisher` (varredura) | Mensagem nunca publicada; mensagem/DLQ perdida; falha de banco prolongada | **Banco** |

A camada 4 é insubstituível porque é a única cuja fonte de verdade é o banco (`fila_atendimento`), e não uma mensagem que precisa existir no broker.

---

## Segurança

`SecurityConfig` configura um **OAuth2 Resource Server** validando JWTs do Keycloak (realm `fila-atendimento`). CSRF desabilitado, sessões `STATELESS`. Um `JwtAuthenticationConverter` extrai `realm_access.roles` e mapeia cada role para a autoridade `ROLE_<role>`.

Tratamento de erros (`GlobalExceptionHandler`, `@RestControllerAdvice`): `IllegalArgumentException` → 400; `NegocioException` → **409**; demais `RuntimeException` → 400 (mensagens de negócio conhecidas) ou 500. Resposta no formato `{ "erro": "..." }`.

---

## Configuração (`application.yml`)

| Chave | Padrão | Descrição |
|-------|--------|-----------|
| `server.port` | `8082` | Porta HTTP. |
| `spring.datasource.*` | `localhost:5432/fila_atendimento` | Postgres (usuário `fila`). |
| `spring.jpa.hibernate.ddl-auto` | `validate` | **O schema é gerenciado externamente** (não é criado pela app). |
| `spring.artemis.broker-url` | `tcp://localhost:61616` | Broker Artemis. |
| `spring.security.oauth2...issuer-uri` | `http://localhost:8080/realms/fila-atendimento` | Emissor dos JWT. |
| `atendimento.exigir-painel-ativo` | `true` | Em produção, recusa chamada sem painel ativo. `false` libera (ex.: teste de carga). Env: `ATENDIMENTO_EXIGIR_PAINEL_ATIVO`. |
| `atendimento.dlq-nome` | `DLQ` | Nome da fila dead-letter no broker. |
| `atendimento.dlq-max-reprocessos` | `2` | Teto de reprocessos da DLQ antes de `FALHA_DLQ`. |

Dependências externas em runtime: Postgres com o schema `fila_atendimento` já criado, broker Artemis (com `DLQ` configurada no `broker.xml`) e Keycloak para validação dos JWT.

---

## Build e execução

```bash
# Build do jar (a partir deste diretório)
mvn clean package

# Executar localmente (requer Postgres, Artemis e Keycloak no ar)
mvn spring-boot:run
# ou
java -jar target/api-atendimento-1.0.0.jar
```

Via Docker (multi-stage; build com Maven/Temurin 21, runtime em `eclipse-temurin:21-jre-alpine`, expõe `8082`):

```bash
docker build -t api-atendimento .
```

Para subir o sistema completo (todos os módulos, broker, banco e Keycloak), use o `docker compose` descrito no [README raiz](README.md).
