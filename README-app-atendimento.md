# app-atendimento

Frontend React usado pelos **atendentes e administradores** da agência. Reúne, em uma única aplicação com rotas por perfil, a triagem (recepção), o atendimento (chamada e ciclo) e a administração (configuração de painéis/estações, permissões de atendentes). Consome a REST API da `api-atendimento`.

> Visão geral do sistema completo está no [README raiz](README.md). O backend que atende este app é o [api-atendimento](README-api-atendimento.md).

---

## Stack

| Item | Versão / Tecnologia |
|------|---------------------|
| Framework | React 18 |
| Build/dev server | Vite 5 |
| Rotas | `react-router-dom` 6 |
| Data fetching | `swr` (revalidação/polling) |
| Autenticação | `keycloak-js` 24 (OIDC) |
| Porta (dev) | `3001` |
| Runtime (container) | build Node 20 → servido por Nginx |

---

## Perfis e rotas

A navegação (`App.jsx`) é montada conforme o papel do usuário (`keycloak.hasRealmRole`):

| Rota | Tela | Acesso |
|------|------|--------|
| `/triagem` | Triagem | `admin` ou `atendente` |
| `/atendimento` | Atendimento (rota padrão) | todos autenticados |
| `/configuracao` | Configuração (painéis, estações, painel↔serviço) | `admin` |
| `/atendentes` | Atendentes (permissões) | `admin` |

### Triagem
Recepciona a pessoa por CPF, consulta agendamentos do dia, gera a senha e insere na fila (`POST /api/triagem/recepcionar`). Ao recepcionar, abre um diálogo com a senha (com opção de imprimir) e registra a senha no histórico do dia.

A tela mostra ainda uma lista **"Aguardando atendimento"** (recarregada a cada 5s via SWR, `GET /api/triagem/atendimentos/{agenciaId}`) com os itens do dia que ainda não foram atendidos (`AGUARDANDO`, `AUSENTE`, `CANCELADO`, `FALHA_DLQ`). Nela o operador tem duas intervenções manuais:

- **Alterar o serviço** de um item que **não** está `AGUARDANDO`: clicar no `Id_Atend` carrega o item no formulário e o "Recepcionar" vira uma atualização (`PUT /api/triagem/atualizar/{id}`), devolvendo-o à fila como `AGUARDANDO`.
- **Republicar** na fila do broker: o botão **Republicar** (`POST /api/triagem/republicar/{id}`) aparece **somente** para itens com status `AGUARDANDO` ou `FALHA_DLQ`. É a rede de segurança operacional para quando a mensagem se perdeu no broker (o item está na tabela mas não há mensagem na fila para o atendente consumir) ou quando o item esgotou as tentativas automáticas de reprocessamento e caiu em `FALHA_DLQ`. A ação recoloca a mensagem na fila e zera o contador anti-loop — o item volta a poder ser chamado. Detalhes do ciclo de DLQ e do `FALHA_DLQ` em [README-api-atendimento.md](README-api-atendimento.md#resiliência-outbox-redelivery-e-dlq).

### Atendimento
Fluxo do atendente:
1. **Iniciar estação** — escolhe tipo (`MESA`/`GUICHE`/`SALA`) e número; o app resolve o `estacaoId` correspondente (`GET /api/atendimento/estacoes/{agenciaId}`). A estação fica guardada em `localStorage`.
2. **Chamar próximo** (`POST /api/atendimento/chamar`) — se o backend responder com `id` nulo (duplicata reentregue ou item ainda não disponível), o app **não** carrega um atendimento ativo e apenas destaca o aviso devolvido, orientando a tentar novamente.
3. **Ciclo** — conforme o status, aciona `ausentar` / `iniciar` / `finalizar` / `cancelar`.

A tela também mostra "Meus Serviços" (`GET /api/atendimento/meus-servicos`) e a "Fila Aguardando" (`GET /api/atendimento/fila-disponivel`), esta recarregada a cada 5s via SWR.

### Configuração e Atendentes (admin)
Telas administrativas para CRUD de painéis e estações, associação painel↔serviço e gestão das permissões (`basica`/`normal`/`especial`) dos atendentes.

A agência é obtida automaticamente do claim `agencia` do token e exibida como campo somente-leitura.

---

## Comunicação com o backend (`services/api.js`)

Um wrapper sobre `fetch` que, antes de cada chamada:
- Garante um token válido com `keycloak.updateToken(30)` (renova se faltar menos de 30s).
- Envia `Authorization: Bearer <jwt>` e `Content-Type: application/json`.
- Em resposta não-OK, lança o corpo de erro (a API devolve `{ "erro": "..." }`); `204` vira `null`.

A base da API vem de `VITE_API_URL`. Os helpers expostos são `api.get/post/put/delete`.

---

## Autenticação

`keycloak-js` inicializado com `onLoad: "login-required"` (realm `fila-atendimento`, clientId `fila-atendimento`). A URL do Keycloak vem de `VITE_KEYCLOAK_URL` (padrão `http://localhost:8080`). A app só renderiza após autenticação.

---

## Configuração (variáveis de build Vite)

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `VITE_KEYCLOAK_URL` | `http://localhost:8080` | URL base do Keycloak. |
| `VITE_API_URL` | `""` (mesma origem) | Base da `api-atendimento` para as chamadas REST. |

---

## Build e execução

```bash
# Dev server (porta 3001)
npm install
npm start

# Build de produção (gera dist/)
npm run build
```

Via Docker (multi-stage: build com `node:20-alpine`, servido por `nginx:alpine` na porta 80, com fallback SPA `try_files ... /index.html`):

```bash
docker build -t app-atendimento .
```

Para subir o sistema completo (frontends, APIs, broker e Keycloak), use o `docker compose` descrito no [README raiz](README.md).
