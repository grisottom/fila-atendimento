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
  └── posicao_fila
```

---

## Como Executar

```bash
docker compose down -v && docker compose up -d --build
```

Aguardar o Keycloak inicializar (~30s) antes de acessar as aplicações.

| URL                          | Descrição                  |
|------------------------------|----------------------------|
| http://localhost:3000        | App Painel                 |
| http://localhost:3001        | App Atendimento            |
| http://localhost:8080        | Keycloak Admin             |
| http://localhost:8161        | Artemis Console            |
| http://localhost:8888        | Traefik Dashboard          |

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
│ atendimento)│  ◄── AtendimentoResponse         │  1. busca próximo│
└─────────────┘                                  │     da fila*     │
                                                 │  2. status →     │
                                                 │     CHAMANDO     │
                                                 │  3. publica no   │
                                                 │     Artemis      │
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

*A seleção do próximo respeita as permissões do atendente: só seleciona atendimentos cujo `servico.permissao_exigida` está entre as roles do atendente.

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

```
  fila_atendimento (status = AGUARDANDO, agencia_id = X, servico compatível)
  │
  ├── com horario_agendado  ──► ordenado por horario_agendado ASC
  │
  └── sem horario_agendado  ──► ordenado por posicao_fila ASC (ordem de chegada)
       (espontâneo)

  Agendados sempre têm prioridade sobre espontâneos.
  Entre agendados: quem tem horário mais cedo é chamado primeiro.
  Entre espontâneos: quem chegou primeiro (menor posicao_fila) é chamado primeiro.
```
