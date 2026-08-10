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
Interface React usada exclusivamente por usuários com role `admin`. Exibe os atendimentos em curso (CHAMANDO, EM_ATENDIMENTO ...) em tempo real via SSE. Cada instância do painel é identificada por agência + número do painel.

### api-painel
API Spring Boot sem persistência. Gerencia conexões SSE dos painéis e cria subscriptions dinâmicas no Artemis por tópico (`agencia.<id>.painel.<n>`). Quando um painel reconecta, solicita replay dos atendimentos ativos.

### app-atendimento
Interface React com três perfis de uso:
- **Triagem** (atendentes com permissão `basica` ou `admin`): recepção da pessoa por CPF, consulta de agendamentos, geração de senha e inserção na fila.
- **Atendimento** (todos os atendentes): seleção de estação, chamada do próximo, controle do ciclo de atendimento.
- **Admin**: configuração de agências, painéis, estações e gestão de atendentes via Keycloak.

### api-atendimento
API Spring Boot com persistência em Postgres. Centraliza toda a lógica de negócio: triagem, fila, atendimento e publicação de eventos no Artemis para o painel.

---

## Controle de Acesso (Keycloak)

Realm: `fila-atendimento`

| Usuário         | Role(s)                    | Acesso                          |
|-----------------|----------------------------|---------------------------------|
| `ger`           | `admin`                    | Todas as telas                  |
| `atend-triagem` | `basica`                   | Triagem + Atendimento           |
| `atend-normal`  | `normal`                   | Atendimento                     |
| `atend-especial`| `especial`                 | Atendimento                     |
| `atend-all`     | `basica`, `normal`, `especial` | Triagem + Atendimento       |

Senha padrão de todos os usuários: `pwd`

A role do atendente determina quais serviços ele pode atender:

| Serviço             | Permissão exigida |
|---------------------|-------------------|
| Serviço Básico      | `basica`          |
| Serviço Normal 01   | `normal`          |
| Serviço Normal 02   | `normal`          |
| Serviço Especial 01 | `especial`        |

---

## Modelo de Dados

```
agencia
  └── painéis (numero_painel, localizacao)
  └── estacões (tipo: MESA | GUICHE | SALA, numero, painel_id)

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
| `01-gerar-usuarios-keycloak.sh`     | Gera `usuarios-teste.json` com N usuários pre-hashed (padrão 1200 agências × 2) |
| `02-importar-usuarios-keycloak.sh`  | Importa os usuários gerados no Keycloak via Admin API em paralelo |
| `03-teste-painel-sse.sh`            | Abre conexões SSE simulando painéis e exibe eventos em tempo real com totalização |
| `04-teste-fluxo-completo.sh`        | Executa o fluxo completo: triagem → chamada → atendimento → finalização |

Fluxo para teste de carga:

```bash
# 1. Sobe o ambiente
docker compose up -d --build

# 2. Gera os usuários de teste (1200 agências, 2400 atendentes)
./teste/01-gerar-usuarios-keycloak.sh

# 3. Importa no Keycloak (paralelo, ~30s)
./teste/02-importar-usuarios-keycloak.sh

# 4. Abre painéis SSE
./teste/03-teste-painel-sse.sh

# 5. Executa fluxo completo (em outro terminal)
./teste/04-teste-fluxo-completo.sh
```

---

## Por que usar um Broker (Artemis) para os eventos do painel?

A comunicação entre `api-atendimento` e `api-painel` poderia ser feita diretamente via chamada REST. O broker foi escolhido por razões arquiteturais:

**Desacoplamento entre produtores e consumidores**
A `api-atendimento` não precisa saber quantas instâncias da `api-painel` existem, nem se estão no ar no momento da publicação. Ela publica no tópico e o Artemis se encarrega da entrega.

**Modelo pub/sub por tópico dinâmico**
Cada painel físico tem seu próprio tópico (`agencia.<id>.painel.<n>`). A `api-painel` cria uma subscription temporária nesse tópico quando o browser conecta via SSE, e o Artemis destrói automaticamente quando o browser desconecta. Isso permite múltiplos painéis ativos simultaneamente, cada um recebendo apenas os eventos da sua agência e número.

**Resiliência a reconexões**
Quando o browser do painel reconecta, a `api-painel` solicita um replay publicando `{ agenciaId, painelId }` na fila `replay-request`. A `api-atendimento` republica os atendimentos ativos no tópico do painel, que já está com o subscriber ativo. Esse mecanismo seria mais complexo de implementar com chamadas REST diretas.

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

## Outbox Pattern — Garantia de publicação na fila

### Problema

Quando a triagem recepciona uma pessoa, o registro é salvo no banco e uma mensagem precisa ser publicada na fila JMS para que atendentes possam consumir. Se o banco commita mas o broker falha (rede, broker reiniciando), o registro fica "AGUARDANDO" sem mensagem correspondente — nunca será chamado.

O cenário inverso também existe: no `chamarProximo`, a mensagem é consumida da fila via `receiveSelected`. Se depois o banco falhar (rollback), a mensagem já foi removida do broker e o registro fica preso.

### Solução

O `OutboxPublisher` implementa um outbox pattern leve usando uma coluna `publicado_no_broker` na própria tabela `fila_atendimento`:

```
  TriagemService                  Banco                    OutboxPublisher (5s)
       │                           │                              │
       │  save(fila)               │                              │
       │  publicadoNoBroker=false  │                              │
       │ ─────────────────────────►│                              │
       │                           │                              │
       │                           │   SELECT ... WHERE           │
       │                           │   publicado_no_broker=false  │
       │                           │   AND status='AGUARDANDO'    │
       │                           │◄─────────────────────────────│
       │                           │                              │
       │                           │                   publica na │
       │                           │                   fila JMS   │
       │                           │                              │──► Artemis
       │                           │                              │
       │                           │   UPDATE                     │
       │                           │   publicado_no_broker=true   │
       │                           │◄─────────────────────────────│
```

Para o cenário do `chamarProximo` (mensagem consumida + rollback no banco), o `AtendimentoService` chama `outboxPublisher.resetarPublicacao(filaId)` no bloco `catch`. Esse método usa `@Transactional(propagation = REQUIRES_NEW)`, garantindo que o reset commita independente do rollback da transação externa. Na próxima execução do scheduler (até 5s), a mensagem é republicada.

### Idempotência

Como o outbox pode republicar mensagens que já existem na fila do broker, o `chamarProximo` tem uma verificação de idempotência: se o registro já não está "AGUARDANDO" quando consumido, descarta a mensagem duplicada e tenta o próximo.

### Performance

Um partial index garante que o scheduler não faça full table scan:

```sql
CREATE INDEX idx_fila_outbox_pendente
    ON fila_atendimento(id)
    WHERE status = 'AGUARDANDO' AND publicado_no_broker = FALSE;
```

O índice se mantém pequeno porque registros pendentes são uma fração mínima da tabela a qualquer momento.

---

## Funcionamento da Fila

### Ciclo completo de um atendimento

```
  PESSOA CHEGA
       │
       ▼
┌─────────────┐     CPF + Serviço     ┌──────────────────┐
│   Triagem   │ ───────────────────►  │  fila_atendimento│
│  (atendente │                       │  status: AGUARDANDO
│   basica)   │  ◄── agendamento?     │  senha: gerada   │
└─────────────┘     (pré-preenche)    └────────┬─────────┘
                                               │
                                    prioridade: agendado > espontâneo
                                    desempate: posicao_fila (chegada)
                                               │
       ┌───────────────────────────────────────┘
       │
       ▼
┌─────────────┐   POST /api/atendimento/chamar   ┌──────────────────┐
│  Atendente  │ ──────────────────────────────►  │  api-atendimento │
│  (tela de   │                                  │                  │
│ atendimento)│  ◄── AtendimentoResponse         │  1. consome da   │
└─────────────┘                                  │     fila JMS*    │
                                                 │  2. status →     │
                                                 │     CHAMANDO     │
                                                 │  3. publica no   │
                                                 │     tópico       │
                                                 └────────┬─────────┘
                                                          │
                              tópico: agencia.<id>.painel.<n>
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

*A seleção do próximo é feita via `receiveSelected` na fila JMS (`agencia.<id>.fila`) com selector de permissões. O broker entrega apenas mensagens cujo `servico.permissao_exigida` está entre as roles do atendente. A mensagem contém o `filaAtendimentoId`, que é então consultado no banco para atualizar o status.

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
              └── cada transição publica evento no Artemis → SSE → painel
```

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
        │                                            │    replay-request│
        │                                            │    (fila JMS)    │
        │                                            │    { agenciaId,  │
        │                                            │      painelId }  │
        │                                            └────────┬─────────┘
        │                                                     │
        │                              fila: replay-request   │
        │                                                     ▼
        │                                            ┌──────────────────┐
        │                                            │ api-atendimento  │
        │                                            │ (ReplayListener) │
        │                                            │                  │
        │                                            │ 1. consulta banco│
        │                                            │    status IN     │
        │                                            │    (CHAMANDO,    │
        │                                            │    EM_ATENDIMENTO│
        │                                            │                  │
        │                                            │ 2. filtra por    │
        │                                            │    painel da     │
        │                                            │    estação       │
        │                                            │                  │
        │                                            │ 3. republica cada│
        │                                            │    atendimento   │
        │                                            │    no tópico     │
        │                                            └────────┬─────────┘
        │                                                     │
        │                         tópico: agencia.x.painel.n  │
        │                                                     ▼
        │                                            ┌──────────────────┐
        └────────────────────────────────────────────│   api-painel     │
                    SSE → cards reaparecem           │  (subscription   │
                    com status correto               │   ativa)         │
                                                     └──────────────────┘
```

O delay de 500ms é necessário porque a subscription JMS no Artemis é criada de forma assíncrona. Sem ele, o replay chegaria antes do subscriber estar pronto e a mensagem seria perdida.

### Prioridade da fila

A prioridade é gerenciada pelo broker JMS via propriedade `JMSPriority` da mensagem:

```
  fila JMS: agencia.<id>.fila
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
