# app-painel

Frontend React do **painel de chamadas**. Exibe, em tempo real, as senhas sendo chamadas/atendidas numa agência, para telas públicas (TVs/monitores). Consome os eventos via SSE da `api-painel`. É usado exclusivamente por usuários com papel `admin`.

> Visão geral do sistema completo está no [README raiz](README.md). O backend que alimenta este app é o [api-painel](README-api-painel.md).

---

## Stack

| Item | Versão / Tecnologia |
|------|---------------------|
| Framework | React 18 |
| Build/dev server | Vite 5 |
| Autenticação | `keycloak-js` 24 (OIDC) |
| Transporte em tempo real | `EventSource` (SSE) |
| Porta (dev) | `3000` |
| Runtime (container) | build Node 20 → servido por Nginx |

---

## Telas

O `App.jsx` bloqueia o acesso de quem não tem o papel `admin` (`keycloak.hasRealmRole("admin")`). Com acesso, há duas telas:

### Ativação do painel (`ConfiguracaoPainel`)
Formulário para "ativar" uma tela como um painel específico:
- **Agência** — preenchida automaticamente do claim `agencia` do token (campo somente-leitura).
- **Número do painel** — informado pelo operador.

A configuração é salva em `localStorage` (chave `painel_config_<username>`), então a tela reabre já ativada após refresh. "Desativar" limpa a configuração e os dados em cache daquele painel.

### Tela de chamadas (`TelaChamadas`)
Abre a conexão SSE e renderiza os cards das senhas:
- Cards com senha, nome, estação e status, coloridos por status (`CHAMANDO`, `EM_ATENDIMENTO`, `AUSENTE`, `FINALIZADO`); o status `CHAMANDO` **pisca** (animação `blink`).
- Lista de **histórico** de chamados abaixo dos cards ativos.
- `FINALIZADO` sai dos cards ativos; `AGUARDANDO` sai do histórico.

---

## Comunicação com o backend

### SSE
Conecta em `GET {VITE_API_PAINEL_URL}/api/painel/sse/{agenciaId}/{painelNumero}?access_token=<jwt>` e escuta o evento `painel-update`. O token vai no **query param** `access_token` porque o `EventSource` nativo do browser não suporta headers customizados (a `api-painel` aceita o token por query param justamente por isso).

### Reconexão com jitter
No `onerror` do `EventSource`, a reconexão não é imediata: aguarda um atraso **aleatório de 1–10s** antes de reabrir. Isso espalha no tempo a rajada de reconexões quando muitos painéis caem juntos (ex.: reinício de pods), evitando o "thundering herd" de `replay-request` no backend.

### Renovação de token
Enquanto o painel está aberto, um timer renova o token a cada 10 min (`keycloak.updateToken`) para manter a sessão do Keycloak viva (evita expiração por SSO Session Idle). Se a renovação falhar, faz logout.

> Observação: o JWT é validado apenas na **abertura** do stream SSE; ele não é revalidado enquanto a conexão fica aberta. A renovação acima mantém a sessão viva para as reconexões.

---

## Autenticação

`keycloak-js` inicializado com `onLoad: "login-required"` (realm `fila-atendimento`, clientId `fila-painel`). A URL do Keycloak vem de `VITE_KEYCLOAK_URL` (padrão `http://localhost:8080`). A app só renderiza após autenticação.

---

## Configuração (variáveis de build Vite)

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `VITE_KEYCLOAK_URL` | `http://localhost:8080` | URL base do Keycloak. |
| `VITE_API_PAINEL_URL` | `""` (mesma origem) | Base da `api-painel` para a conexão SSE. |

---

## Build e execução

```bash
# Dev server (porta 3000)
npm install
npm start

# Build de produção (gera dist/)
npm run build
```

Via Docker (multi-stage: build com `node:20-alpine`, servido por `nginx:alpine` na porta 80, com fallback SPA `try_files ... /index.html`):

```bash
docker build -t app-painel .
```

Para subir o sistema completo (frontends, APIs, broker e Keycloak), use o `docker compose` descrito no [README raiz](README.md).
