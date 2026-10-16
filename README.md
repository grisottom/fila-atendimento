# Fila de Atendimento

Sistema de gerenciamento de fila para agências, com suporte a atendimentos agendados e espontâneos, painel de chamadas em tempo real e controle de acesso via Keycloak.

Este documento é a **visão geral** do sistema. Os detalhes técnicos de cada módulo ficam nos READMEs específicos:

| Módulo | Tipo | Documentação |
|--------|------|--------------|
| `app-atendimento` | Frontend React (triagem/atendimento/admin) | [README-app-atendimento.md](README-app-atendimento.md) |
| `app-painel` | Frontend React (painel de chamadas) | [README-app-painel.md](README-app-painel.md) |
| `api-atendimento` | Backend Spring Boot (lógica de negócio + persistência) | [README-api-atendimento.md](README-api-atendimento.md) |
| `api-painel` | Backend Spring Boot (SSE dos painéis) | [README-api-painel.md](README-api-painel.md) |

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

As duas APIs **não** conversam por REST entre si: a comunicação é 100% via Artemis (tópicos e filas JMS). A `api-atendimento` é a única com banco e detém toda a lógica de negócio; a `api-painel` é stateless e só faz a ponte broker↔browser (SSE).

---

## Módulos

- **app-painel** — Interface React usada exclusivamente por usuários com role `admin`. Exibe os atendimentos em curso (CHAMANDO, EM_ATENDIMENTO...) em tempo real via SSE. Cada painel é identificado por agência + número. Detalhes em [README-app-painel.md](README-app-painel.md).
- **app-atendimento** — Interface React com três perfis: **Triagem** (recepção por CPF, agendamentos, geração de senha), **Atendimento** (estação, chamar próximo, ciclo do atendimento) e **Admin** (painéis, estações, associação painel↔serviço, permissões). Detalhes em [README-app-atendimento.md](README-app-atendimento.md).
- **api-atendimento** — Backend Spring Boot com persistência em Postgres. Centraliza triagem, fila, atendimento e publicação de eventos no Artemis. Detalhes em [README-api-atendimento.md](README-api-atendimento.md).
- **api-painel** — Backend Spring Boot sem persistência. Consome os eventos do Artemis (um listener compartilhado no wildcard `agencia.*.painel.*`) e os repassa aos painéis via SSE; solicita replay ao reconectar. Detalhes em [README-api-painel.md](README-api-painel.md).

A agência do usuário é obtida automaticamente do token JWT (atributo `agencia` no Keycloak) e exibida como campo somente-leitura nas telas.

---

## Controle de Acesso

### Keycloak (papéis)

Realm: `fila-atendimento`. Do Keycloak vêm os **papéis** de acesso:

| Role        | Descrição                     | Acesso HTTP                        |
|-------------|-------------------------------|------------------------------------|
| `admin`     | Gerente da agência            | Todas as rotas (`/api/admin/**`, `/api/triagem/**`, `/api/atendimento/**`) |
| `atendente` | Atendente (qualquer nível)    | Triagem (`/api/triagem/**`) e Atendimento (`/api/atendimento/**`) |

Atributo do usuário no Keycloak: `agencia` (ex.: `agencia-01`), mapeado no token JWT via protocol mapper.

### Banco de dados (permissões de atendimento)

As **permissões de atendimento** (quais serviços o atendente pode chamar) são gerenciadas no banco, nas tabelas `atendente` e `permissoes_atendente`:

| Permissão  | Serviços atendidos                         |
|------------|--------------------------------------------|
| `basica`   | Serviço Básico                             |
| `normal`   | Serviço Normal 01, Serviço Normal 02       |
| `especial` | Serviço Especial 01                        |

Um atendente pode ter múltiplas permissões; o admin as altera via API REST a qualquer momento.

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

paineis_servicos (painel_id, servico_id)   — associação N:N painel↔serviço

atendente (username, agencia_id)
  └── permissoes_atendente (permissao: basica | normal | especial)

pessoa (cpf, nome)
  └── agendamento (agencia_id, servico_id, data_hora)

fila_atendimento   — agregado central (status, horários, senha, atendente, estação,
                     triagem_uuid, posicao_fila, publicado_no_broker, ...)
```

O detalhamento completo das colunas de `fila_atendimento` e das entidades está em [README-api-atendimento.md](README-api-atendimento.md#modelo-de-dados-entidades).

### Relacionamento painel ↔ serviço

O painel não está atrelado à estação, e sim ao **serviço**. Ao chamar o próximo, o sistema identifica o serviço do atendimento, busca em `paineis_servicos` quais painéis estão associados a ele e publica o evento em **todos** eles. Se o serviço não tiver nenhum painel associado, o chamado é **recusado** (409).

---

## Fluxo de um atendimento (resumo)

1. **Triagem** recepciona a pessoa (CPF + serviço), gera a senha e insere a linha em `fila_atendimento` (status `AGUARDANDO`), publicando uma mensagem na queue JMS `agencia.<id>.fila`.
2. **Atendente** chama o próximo: a `api-atendimento` consome a mensagem da queue (com selector pelas permissões do atendente), atualiza o status para `CHAMANDO` e publica o evento nos tópicos dos painéis do serviço.
3. **api-painel** recebe o evento e o repassa ao browser via SSE; o painel exibe a senha.
4. O atendente percorre o ciclo: `iniciar` → `finalizar`/`cancelar`, ou `ausentar` (volta ao fim da fila). Cada transição publica um evento nos painéis.

```
                    ┌──────────────┐
                    │  AGUARDANDO  │◄──────────────────────────┐
                    └──────┬───────┘                           │
                           │ chamar próximo                    │
                           ▼                                   │
                    ┌──────────────┐                           │
                    │   CHAMANDO   │                           │
                    └──────┬───────┘                           │
                     ┌─────┴───────────┐                       │
                     ▼ iniciar         ▼ ausentar              │
              ┌──────────────┐  ┌──────────────┐               │
              │EM_ATENDIMENTO│  │    AUSENTE   │──────────────►┘
              └──────┬───────┘  └──────────────┘   volta ao fim
               ┌─────┴─────────┐                     da fila
               ▼ finalizar     ▼ cancelar
         ┌──────────────┐  ┌──────────────┐
         │  FINALIZADO  │  │   CANCELADO  │
         └──────────────┘  └──────────────┘
```

Pontos de projeto importantes — detalhados nos READMEs de módulo:
- **Por que um broker (Artemis) em vez de REST entre as APIs**, modelo pub/sub e replay ao reconectar: [README-api-painel.md](README-api-painel.md).
- **Prioridade na fila** (agendados antes de espontâneos), **outbox pattern**, **redelivery** e **DLQ** (reprocessamento e `FALHA_DLQ`): [README-api-atendimento.md](README-api-atendimento.md#resiliência-outbox-redelivery-e-dlq).
- **Heartbeat/liveness dos painéis** e o ping SSE: [README-api-painel.md](README-api-painel.md) e [README-api-atendimento.md](README-api-atendimento.md).

---

## Como Executar

```bash
docker compose down -v && docker compose up -d --build
```

O Keycloak usa H2 em memória e importa apenas os usuários essenciais (~7), com startup rápido (~20–30s). Aguarde o health check antes de acessar as aplicações.

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
| `02-teste-painel-sse.sh`            | Abre conexões SSE simulando painéis e exibe eventos em tempo real |
| `03-teste-atendimento.sh`           | Teste de carga: triagem → chamada → atendimento → finalização em paralelo |
| `04-limpar-cache.sh`                | Remove tokens em cache e logs de execuções de teste anteriores |

Fluxo para teste de carga:

```bash
# 1. Sobe o ambiente
docker compose up -d --build

# 2. Cria usuários de teste no Keycloak + banco (idempotente)
./teste/01-setup-usuarios-keycloak.sh

# 3. Abre painéis SSE (em outro terminal)
./teste/02-teste-painel-sse.sh

# 4. Executa fluxo completo
./teste/03-teste-atendimento.sh
```
