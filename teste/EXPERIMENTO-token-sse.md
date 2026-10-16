# Experimento: expiração de token/sessão no app-painel (código atual)

Objetivo: observar o que acontece com a conexão SSE do painel quando os tempos de
token/sessão do Keycloak são curtos, usando o **código atual** (renovação por timer
fixo, sem recriar o `EventSource`). Serve de baseline antes de implementar a
renovação proativa do TODO.

> Tudo aqui é isolado em arquivos `*.experimento-token.*`. Nenhuma config de
> produção é alterada. Para encerrar, basta subir de novo sem o arquivo de override.

## Tempos aplicados

| Parâmetro | Experimento | Produção (referência) |
|-----------|-------------|------------------------|
| Renovação no front (app-painel) | **1 min** (`VITE_TOKEN_REFRESH_MS=60000`) | 10 min (fixo) |
| `accessTokenLifespan` | **5 min** | 1 h |
| `ssoSessionIdleTimeout` | **3 min** | 30 min (default) |
| `ssoSessionMaxLifespan` | **7 min** | 10 h (default) |

Observe a relação proposital entre eles:
- Renovação (1 min) **<** token (5 min): o token é renovado bem antes de expirar.
- Renovação (1 min) **<** Idle (3 min): cada renovação reseta o Idle → a sessão não
  deveria expirar por ociosidade enquanto o painel estiver aberto renovando.
- Session Max (7 min) é um **teto absoluto**: nenhuma renovação o estende.

## Como rodar

O `down -v` é obrigatório: o Keycloak só reimporta o realm (com os tempos curtos)
num banco vazio.

```bash
# 1. Derruba tudo e ZERA os volumes (Keycloak precisa reimportar o realm)
docker compose -f docker-compose.yml -f docker-compose.experimento-token.yml down -v

# 2. Sobe com o override do experimento (rebuild do app-painel p/ pegar o build-arg)
docker compose -f docker-compose.yml -f docker-compose.experimento-token.yml up -d --build

# 3. Aguarde o health do Keycloak e abra o painel
#    http://localhost:3000  (login: ger / pwd)  → ativar um painel (ex.: agência agencia-01, painel 1)
```

Deixe o DevTools aberto (Console + Network, filtro "sse"/EventStream) e, de preferência,
rode uma triagem/chamada em paralelo (http://localhost:3001) para gerar eventos no painel.

## O que observar e registrar (marque os horários)

1. **t ≈ 1 min, 2 min, ...** — a cada renovação, confirmar no Network uma chamada ao
   endpoint `token` do Keycloak retornando 200. O stream SSE **continua o mesmo**
   (não há reconexão) — o token novo NÃO entra no stream já aberto.
2. **t ≈ 5 min (token expira)** — o stream aberto **continua entregando eventos**?
   (esperado: sim; a api-painel não revalida o JWT no stream já aberto). Anotar.
3. **Forçar uma reconexão depois dos 5 min** — ex.: `docker restart fila-api-painel`
   (ou derrubar/subir a api-painel). O `onerror` dispara `conectarSSE`, que faz
   `updateToken(30)` antes de reabrir. Reconectou com sucesso? Houve replay dos
   cards? Anotar o resultado e se apareceu algum 401 no Network.
4. **t ≈ 7 min (Session Max)** — este é o teto absoluto. Na próxima renovação após
   os 7 min, o `updateToken` deve **falhar** → o código cai no `catch` →
   `keycloak.logout()` → painel vai para a tela de login. Confirmar o horário em que
   isso ocorre e que foi o Session Max (e não o Idle) que disparou.
5. **Sessão ociosa vs. renovando** — como a renovação (1 min) < Idle (3 min), a
   sessão NÃO deve cair por Idle enquanto o painel renova. Se quiser ver o Idle
   disparar isolado, feche o painel (para parar as renovações) logo após logar e
   observe a sessão expirar por ociosidade em ~3 min (checável no Admin Console →
   Sessions, ou tentando uma ação que exija token).

### Hipótese de resultado (código atual)

- Stream estável sobrevive à expiração do access token (sem revalidação no stream).
- Reconexões dentro da janela da sessão SSO se recuperam via `updateToken` na
  reabertura.
- O **Session Max (7 min)** é o que efetivamente derruba o painel para login —
  nenhuma renovação o contorna. Esse é o ponto que a renovação proativa do TODO
  **também não resolve** (só melhora a renovação do access token, não o teto da sessão).

## Encerrar o experimento (voltar ao normal)

```bash
# Sobe de novo SEM o override → volta ao realm e tempos de produção
docker compose -f docker-compose.yml down -v
docker compose -f docker-compose.yml up -d --build
```

## Artefatos deste experimento (descartáveis)

- `docker-compose.experimento-token.yml` — override (realm + build-arg do front).
- `infra/keycloak/realm-export.experimento-token.json` — realm com tempos curtos.
- `app-painel/.../TelaChamadas.jsx` — renovação parametrizada por `VITE_TOKEN_REFRESH_MS`
  (retrocompatível: sem a env, mantém 10 min).
- `app-painel/Dockerfile` — `ARG/ENV VITE_TOKEN_REFRESH_MS` (default vazio → sem efeito).

As duas últimas mudanças são retrocompatíveis e podem ficar; as duas primeiras são
exclusivas do experimento.
