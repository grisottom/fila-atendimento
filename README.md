# Fila de Atendimento

Sistema de gerenciamento de fila para agências, com suporte a atendimentos agendados e espontâneos, painel de chamadas em tempo real e controle de acesso via Keycloak.

---

## Visão Geral da Arquitetura

```
┌─────────────────────────────────────────────────────────────────┐
│                        Docker Compose                           │
│                                                                 │
│  :3000                          :3001                           │
│  ┌──────────────┐              ┌──────────────────────────┐     │
│  │  app-painel  │              │    app-atendimento       │     │
│  │   (React)    │              │       (React)            │     │
│  └──────┬───────┘              └────────────┬─────────────┘     │
│         │                                   │                   │
│  ┌──────▼───────┐              ┌────────────▼─────────────┐     │
│  │  api-painel  │              │    api-atendimento       │     │
│  │ (Spring Boot)│              │     (Spring Boot)        │     │
│  │   :8081      │              │        :8082             │     │
│  └──────┬───────┘              └──┬──────────────┬────────┘     │
│         │                         │              │              │
│         │      ┌──────────────────┘              │              │
│         │      │                                 │              │
│  ┌──────▼──────▼──┐                   ┌──────────▼──────────┐   │
│  │    Artemis     │                   │      Postgres       │   │
│  │  (ActiveMQ)    │                   │   fila_atendimento  │   │
│  │   :61616       │                   │      :5432          │   │
│  └────────────────┘                   └─────────────────────┘   │
│                                                                 │
│  ┌─────────────────────┐   ┌──────────────────────────────┐     │
│  │      Keycloak       │   │           Traefik            │     │
│  │   (Auth / OIDC)     │   │  (Reverse Proxy / Roteador)  │     │
│  │      :8080          │   │         :3000 / :3001        │     │
│  └─────────────────────┘   └──────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘
```

O Traefik roteia as requisições:
- `localhost:3000/*` → `app-painel` (frontend estático)
- `localhost:3000/api/*` → `api-painel`
- `localhost:3001/*` → `app-atendimento` (frontend estático)
- `localhost:3001/api/*` → `api-atendimento`

---

## Módulos

### app-painel
Interface React usada exclusivamente por usuários com role `admin`. Exibe os atendimentos em curso (CHAMANDO, EM_ATENDIMENTO ...) em tempo real via SSE. Cada instância do painel é identificada por agência + número do painel. A agência é obtida automaticamente do token do usuário.

### api-painel
API Spring Boot sem persistência. Gerencia conexões SSE dos painéis e cria subscriptions dinâmicas no Artemis por tópico (`agencia.<id>.painel.<n>`). Quando um painel reconecta, solicita replay dos atendimentos ativos.

### app-atendimento
Interface React com três perfis de uso:
- **Triagem** (atendentes com permissão `basica` ou `admin`): recepção da pessoa por CPF, consulta de agendamentos, geração de senha e inserção na fila.
- **Atendimento** (todos os atendentes): seleção de estação, chamada do próximo, controle do ciclo de atendimento.
- **Admin**: configuração de painéis, estações, associação painel↔serviço e gestão de permissões de atendentes.

A agência do usuário é obtida automaticamente do token JWT (atributo `agencia` no Keycloak) e exibida como campo somente-leitura em todas as telas.

### api-atendimento
API Spring Boot com persistência em Postgres. Centraliza toda a lógica de negócio: triagem, fila, atendimento e publicação de eventos no Artemis para os painéis associados ao serviço.

---

## Controle de Acesso

### Keycloak (Papéis)

Realm: `fila-atendimento`

Do Keycloak vêm os **papéis** de acesso:

| Role        | Descrição                     | Acesso HTTP                        |
|-------------|-------------------------------|------------------------------------|
| `admin`     | Gerente da agência            | Todas as rotas (`/api/admin/**`, `/api/triagem/**`, `/api/atendimento/**`) |
| `atendente` | Atendente (qualquer nível)    | Triagem (`/api/triagem/**`) e Atendimento (`/api/atendimento/**`) |

Atributo do usuário no Keycloak: `agencia` (ex: `agencia-01`) — mapeado no token JWT via protocol mapper.

### Banco de Dados (Permissões de Atendimento)

As **permissões de atendimento** (que determinam quais serviços o atendente pode chamar) são gerenciadas no banco de dados, nas tabelas `atendente` e `permissoes_atendente`:

| Permissão  | Serviços atendidos                         |
|------------|--------------------------------------------|
| `basica`   | Serviço Básico                             |
| `normal`   | Serviço Normal 01, Serviço Normal 02       |
| `especial` | Serviço Especial 01                        |

Um atendente pode ter múltiplas permissões. O admin pode alterar permissões a qualquer momento via API REST.

### Usuários pré-configurados

| Usuário          | Role Keycloak | Permissões (banco)              |
|------------------|---------------|---------------------------------|
| `ger`            | `admin`       | (todas, implícito)              |
| `atend-triagem`  | `atendente`   | `basica`                        |
| `atend-triagem-2`| `atendente`   | `basica`                        |
| `atend-normal`   | `atendente`   | `normal`                        |
| `atend-especial` | `atendente`   | `especial`                      |
| `atend-all`      | `atendente`   | `basica`, `normal`, `especial`  |
| `atend-all-2`    | `atendente`   | `basica`, `normal`, `especial`  |

Senha padrão de todos os usuários: `pwd`

---

## Modelo de Dados

```
agencia (id, nome)
  └── painel (numero_painel, localizacao)
  └── estacao (tipo: MESA | GUICHE | SALA, numero, localizacao)

servico (id, nome, permissao_exigida)

paineis_servicos (painel_id, servico_id)
  — Associação N:N entre painéis e serviços
  — Define quais painéis recebem eventos de um dado serviço

atendente (username, agencia_id)
  └── permissoes_atendente (permissao: basica | normal | especial)

pessoa (cpf, nome)
  └── agendamento (agencia_id, servico_id, data_hora)

fila_atendimento
  ├── agencia_id
  ├── cpf → pessoa
  ├── servico_id → servico
  ├── senha (5 chars, maiúsculas + números)
  ├── status: AGUARDANDO | CHAMANDO | EM_ATENDIMENTO | AUSENTE | FINALIZADO | CANCELADO
  ├── estacao_id → estacao
  ├── atendente_username
  ├── horario_agendado (null = espontâneo)
  ├── horario_chegada
  ├── horario_chamada
  ├── horario_inicio_atendimento
  ├── horario_fim_atendimento
  ├── posicao_fila
  └── publicado_no_broker (flag do outbox pattern)
```

### Relacionamento Painel ↔ Serviço

O painel não está atrelado à estação, e sim ao serviço. Quando um atendente chama o próximo:
1. O sistema identifica o serviço do atendimento
2. Busca na tabela `paineis_servicos` quais painéis estão associados àquele serviço
3. Publica o evento em **todos** os painéis associados
4. Se o serviço não tiver nenhum painel associado, o chamado é **recusado** com mensagem de erro

---

## Como Executar

```bash
docker compose down -v && docker compose up -d --build
```

O Keycloak usa H2 em memória e importa apenas os usuários essenciais (~7), resultando em startup rápido (~20-30s). Aguarde o health check antes de acessar as aplicações.

| URL                          | Descrição                  |
|------------------------------|----------------------------|
| http://localhost:3000        | App Painel                 |
| http://localhost:3001        | App Atendimento            |
| http://localhost:8080        | Keycloak Admin             |
| http://localhost:8161        | Artemis Console            |
| http://localhost:8888        | Traefik Dashboard          |

### Scripts de teste (pasta `teste/`)

| Script                              | Descrição                                                        |
|-------------------------------------|------------------------------------------------------------------|
| `01-setup-usuarios-keycloak.sh`     | Cria atendentes no Keycloak (role `atendente`) e no banco (permissões) |
| `02-teste-atendimento.sh`           | Teste de carga: triagem → chamada → atendimento → finalização em paralelo |
| `03-teste-painel-sse.sh`            | Abre conexões SSE simulando painéis e exibe eventos em tempo real |

Fluxo para teste de carga:

```bash
# 1. Sobe o ambiente
docker compose up -d --build

# 2. Cria usuários de teste no Keycloak + banco (idempotente)
./teste/01-setup-usuarios-keycloak.sh

# 3. Abre painéis SSE (em outro terminal)
./teste/03-teste-painel-sse.sh

# 4. Executa fluxo completo
./teste/02-teste-atendimento.sh
```

---

## Por que usar um Broker (Artemis) para os eventos do painel?

A comunicação entre `api-atendimento` e `api-painel` poderia ser feita diretamente via chamada REST. O broker foi escolhido por razões arquiteturais:

**Desacoplamento entre produtores e consumidores**
A `api-atendimento` não precisa saber quantas instâncias da `api-painel` existem, nem se estão no ar no momento da publicação. Ela publica no tópico e o Artemis se encarrega da entrega.

**Modelo pub/sub por tópico dinâmico**
Cada painel físico tem seu próprio tópico (`agencia.<id>.painel.<n>`). A `api-painel` cria uma subscription temporária nesse tópico quando o browser conecta via SSE, e o Artemis destrói automaticamente quando o browser desconecta. Isso permite múltiplos painéis ativos simultaneamente, cada um recebendo apenas os eventos da sua agência e número.

**Resiliência a reconexões**
Quando o browser do painel reconecta, a `api-painel` solicita um replay publicando `{ agenciaId, painelId }` na fila `replay-request`. A `api-atendimento` republica os atendimentos ativos no tópico do painel, que já está com o subscriber ativo. Esse mecanismo seria mais complexo de implementar com chamadas REST diretas. O mecanismo é explicado em detalhes na seção "Replay ao reconectar o Painel" abaixo.

**Escalabilidade horizontal**
Com múltiplas instâncias da `api-painel` (ex: em Kubernetes), o Artemis garante que a mensagem chegue exatamente na instância onde o browser do painel está conectado, pois a subscription temporária está vinculada àquela instância.

```
  api-atendimento          Artemis              api-painel
       │                     │                      │
       │  publica no tópico  │                      │
       │  agencia.01.painel.1│                      │
       │ ──────────────────► │                      │
       │                     │  entrega somente na  │
       │                     │  instância com sub   │
       │                     │  ativa para o tópico │
       │                     │ ────────────────────►│
       │                     │                      │  SSE event
       │                     │                      │ ──────────► browser
```

---

## Funcionamento da Fila de Atendimento

O sistema usa duas estruturas complementares com nomes parecidos:

| Conceito | Onde vive | Propósito |
|----------|-----------|-----------|
| **Tabela `fila_atendimento`** | Postgres | Registro persistente de cada atendimento (status, timestamps, atendente, estação) |
| **Queue JMS `agencia.<id>.fila`** | Artemis (broker) | Fila de mensagens para consumo pelo atendente; cada mensagem aponta para um registro da tabela acima via `filaAtendimentoId` |
| **Tópico JMS `agencia.<id>.painel.<n>`** | Artemis (broker) | Canal pub/sub para notificar painéis sobre mudanças de status |

O fluxo normal: a triagem cria um registro na **tabela** e publica uma mensagem na **queue JMS**. Quando o atendente chama o próximo, ele consome da **queue JMS**, atualiza o registro na **tabela**, e publica nos **tópicos JMS** dos painéis.

### Ciclo completo de um atendimento

```
  PESSOA CHEGA
       │
       ▼
┌─────────────┐     CPF + Serviço     ┌──────────────────────────────────┐
│   Triagem   │ ───────────────────►  │  1. INSERT na tabela             │
│  (atendente │                       │     fila_atendimento             │
│   basica)   │  ◄── agendamento?     │     (status: AGUARDANDO)         │
└─────────────┘     (pré-preenche)    │                                  │
                                      │  2. Publica mensagem na          │
                                      │     queue JMS                    │
                                      │     agencia.<id>.fila            │
                                      │     (contém filaAtendimentoId)   │
                                      └────────────────┬─────────────────┘
                                                       │
                                            prioridade: agendado > espontâneo
                                            desempate: ordem de chegada (FIFO)
                                                       │
       ┌───────────────────────────────────────────────┘
       │
       ▼
┌─────────────┐   POST /api/atendimento/chamar   ┌──────────────────────────────┐
│  Atendente  │ ──────────────────────────────►  │  api-atendimento             │
│  (tela de   │                                  │                              │
│ atendimento)│  ◄── AtendimentoResponse         │  1. Consome mensagem da      │
└─────────────┘                                  │     queue JMS*               │
                                                 │  2. Busca registro na tabela │
                                                 │     fila_atendimento         │
                                                 │  3. Verifica painéis do      │
                                                 │     serviço**                │
                                                 │  4. UPDATE status →          │
                                                 │     CHAMANDO na tabela       │
                                                 │  5. Publica evento nos       │
                                                 │     tópicos JMS dos painéis  │
                                                 └────────────────┬─────────────┘
                                                                  │
                              tópicos JMS: agencia.<id>.painel.<n> (1..N)
                                                                  │
                                                                  ▼
                                                         ┌────────────────┐
                                                         │   api-painel   │
                                                         │  (subscription │
                                                         │   dinâmica)    │
                                                         └────────┬───────┘
                                                                  │  SSE event
                                                                  ▼
                                                         ┌────────────────┐
                                                         │   app-painel   │
                                                         │  card CHAMANDO │
                                                         │  (blink label) │
                                                         └────────────────┘
```

*A seleção do próximo é feita via `receiveSelected` na queue JMS (`agencia.<id>.fila`) com selector de permissões do atendente (consultadas no banco). O broker entrega apenas mensagens cujo `permissao` corresponde às permissões do atendente. A mensagem contém o `filaAtendimentoId`, usado para localizar e atualizar o registro na tabela `fila_atendimento`.

**Se o serviço não tiver painéis associados na tabela `paineis_servicos`, o chamado é recusado com erro e a mensagem é devolvida à queue (via outbox).

### Transições de status

```
                    ┌──────────────┐
                    │  AGUARDANDO  │◄──────────────────────────┐
                    └──────┬───────┘                           │
                           │ chamar próximo                    │
                           ▼                                   │
                    ┌──────────────┐                           │
              ┌────►│   CHAMANDO   │                           │
              │     └──────┬───────┘                           │
              │            │                                   │
              │     ┌──────┴──────────────┐                    │
              │     │                     │                    │
              │     ▼ iniciar             ▼ ausentar           │
              │  ┌──────────────┐  ┌──────────────┐            │
              │  │EM_ATENDIMENTO│  │    AUSENTE   │───────────►┘
              │  └──────┬───────┘  └──────────────┘  volta ao fim
              │         │                              da fila
              │  ┌──────┴──────────────┐
              │  │                     │
              │  ▼ finalizar           ▼ cancelar
              │  ┌──────────────┐  ┌──────────────┐
              │  │  FINALIZADO  │  │   CANCELADO  │
              │  └──────────────┘  └──────────────┘
              │
              └── cada transição publica evento nos painéis do serviço → SSE
```

---

## Outbox Pattern — Garantia de publicação na queue JMS

### Problema

Quando a triagem recepciona uma pessoa, um registro é inserido na tabela `fila_atendimento` e uma mensagem precisa ser publicada na queue JMS (`agencia.<id>.fila`) para que atendentes possam consumir. Se o banco commita mas o broker falha (rede, broker reiniciando), o registro fica "AGUARDANDO" na tabela sem mensagem correspondente na queue — nunca será chamado.

O cenário inverso também existe: no `chamarProximo`, a mensagem é consumida da queue JMS via `receiveSelected`. Se depois o banco falhar (rollback na atualização da tabela `fila_atendimento`), a mensagem já foi removida da queue e o registro fica preso.

### Solução

O `OutboxPublisher` implementa um outbox pattern leve usando a coluna `publicado_no_broker` na tabela `fila_atendimento`:

```
  TriagemService                  Banco (tabela             OutboxPublisher (a cada ~5s)
       │                          fila_atendimento)               │
       │                                │                         │
       │  INSERT registro               │                         │
       │  publicado_no_broker=false     │                         │
       │ ──────────────────────────────►│                         │
       │                                │                         │
       │                                │   SELECT ... WHERE      │
       │                                │   publicado_no_broker   │
       │                                │   = false               │
       │                                │   AND status=           │
       │                                │   'AGUARDANDO'          │
       │                                │◄────────────────────────│
       │                                │                         │
       │                                │           publica na    │
       │                                │           queue JMS     │
       │                                │           agencia.x.fila│
       │                                │                         │──► Artemis
       │                                │                         │
       │                                │   UPDATE                │
       │                                │   publicado_no_broker   │
       │                                │   = true                │
       │                                │◄────────────────────────│
```

Para o cenário do `chamarProximo`, em caso de erro (mensagem consumida da queue + rollback na tabela), no bloco `catch` o `AtendimentoService` chama `outboxPublisher.resetarPublicacao(filaId)`. Esse método usa `@Transactional(propagation = REQUIRES_NEW)`, garantindo que o reset na tabela commita independente do rollback da transação externa. Na próxima execução do scheduler (~5s), a mensagem é republicada na queue JMS.

### Idempotência

Como o outbox pode republicar mensagens que já existem na queue JMS, o `chamarProximo` tem uma verificação de idempotência: ao consumir uma mensagem, busca o registro na tabela `fila_atendimento` pelo `filaAtendimentoId` — se o status já não é "AGUARDANDO", descarta a mensagem duplicada e tenta consumir a próxima.

### Performance

Um partial index garante que o scheduler não faça full table scan:

```sql
CREATE INDEX idx_fila_outbox_pendente
    ON fila_atendimento(id)
    WHERE status = 'AGUARDANDO' AND publicado_no_broker = FALSE;
```

O índice se mantém pequeno porque registros pendentes são uma fração mínima da tabela a qualquer momento.

---

### Replay ao reconectar o Painel

Quando o browser do painel reconecta (refresh, queda de rede, reinício do servidor), o estado em memória é perdido. O mecanismo de replay recupera os atendimentos ativos:

```
  browser abre SSE
        │
        ▼
┌───────────────┐   registrar(agenciaId, painelId)   ┌──────────────────┐
│  app-painel   │ ─────────────────────────────────► │   api-painel     │
└───────────────┘                                    │                  │
        ▲                                            │ 1. cria container│
        │                                            │    JMS no tópico │
        │  SSE events                                │    agencia.x.    │
        │                                            │    painel.n      │
        │                                            │                  │
        │                                            │ 2. aguarda 500ms │
        │                                            │    (subscriber   │
        │                                            │    registrar no  │
        │                                            │    Artemis)      │
        │                                            │                  │
        │                                            │ 3. publica em    │
        │                                            │    queue JMS     │
        │                                            │    replay-request│
        │                                            │    { agenciaId,  │
        │                                            │      painelId }  │
        │                                            └────────┬─────────┘
        │                                                     │
        │                       queue JMS: replay-request     │
        │                                                     ▼
        │                                            ┌──────────────────┐
        │                                            │ api-atendimento  │
        │                                            │ (ReplayListener) │
        │                                            │                  │
        │                                            │ 1. consulta tabela
        │                                            │    fila_atendimento
        │                                            │    status IN     │
        │                                            │    (CHAMANDO,    │
        │                                            │    EM_ATENDIMENTO)
        │                                            │                  │
        │                                            │ 2. filtra por    │
        │                                            │    serviço →     │
        │                                            │    paineis_serv. │
        │                                            │    → painelId    │
        │                                            │                  │
        │                                            │ 3. republica cada│
        │                                            │    atendimento no│
        │                                            │    tópico JMS    │
        │                                            └────────┬─────────┘
        │                                                     │
        │                  tópico JMS: agencia.x.painel.n     │
        │                                                     ▼
        │                                            ┌──────────────────┐
        └────────────────────────────────────────────│   api-painel     │
                    SSE → cards reaparecem           │  (subscription   │
                    com status correto               │   ativa)         │
                                                     └──────────────────┘
```

O delay de 500ms é necessário porque a subscription no tópico JMS do Artemis é criada de forma assíncrona. Sem ele, o replay chegaria antes do subscriber estar pronto e a mensagem seria perdida.

### Prioridade na queue JMS

A prioridade é gerenciada pelo broker via propriedade `JMSPriority` da mensagem na queue `agencia.<id>.fila`:

```
  queue JMS: agencia.<id>.fila
  │
  ├── prioridade 9 (agendados)  ──► consumidos primeiro pelo receiveSelected
  │
  └── prioridade 4 (espontâneos) ──► consumidos após os agendados
```

O selector JMS filtra por permissão do atendente:
```
permissao IN ('basica', 'normal', 'especial')
```

Agendados sempre têm prioridade sobre espontâneos (prioridade JMS mais alta).
Dentro da mesma prioridade, o broker entrega na ordem de chegada (FIFO).
