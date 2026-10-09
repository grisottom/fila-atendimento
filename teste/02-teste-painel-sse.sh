#!/bin/bash
# teste-massivo-sse.sh
# Simula conexões SSE para múltiplas agências e painéis sem usar navegador.
# Cada agência usa o token do seu admin (admin-agencia-XXXX).
# Eventos são exibidos em TEMPO REAL no terminal conforme chegam.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

BASE_URL="$BASE_URL_PAINEL"
CLIENT_ID="fila-painel"
PASSWORD="pwd"

TOTAL_CONEXOES=$((NUM_AGENCIAS * NUM_PAINEIS_POR_AGENCIA))

echo "=== Teste Massivo SSE (tempo real) ==="
echo "Agências: $NUM_AGENCIAS"
echo "Painéis por agência: $NUM_PAINEIS_POR_AGENCIA"
echo "Total de conexões SSE: $TOTAL_CONEXOES"
echo ""

# ─── OBTER TOKENS (com cache) ────────────────────────────
TOKEN_DIR="$LOG_DIR/tokens-painel"
mkdir -p "$TOKEN_DIR"

# Verifica se um token ainda é válido (não expirado, margem de 5 min)
token_valido() {
  local TOKEN=$1
  [ -z "$TOKEN" ] || [ "$TOKEN" == "null" ] && return 1
  local EXP
  EXP=$(echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq -r '.exp' 2>/dev/null)
  [ -z "$EXP" ] || [ "$EXP" == "null" ] && return 1
  local AGORA=$(date +%s)
  [ $((EXP - 300)) -gt $AGORA ]
}

# Verifica se tokens existentes ainda são válidos
REUSAR_TOKENS=false
PRIMEIRO_ARQUIVO="$TOKEN_DIR/agencia-0001"
if [ -f "$PRIMEIRO_ARQUIVO" ]; then
  PRIMEIRO_TOKEN=$(cat "$PRIMEIRO_ARQUIVO" 2>/dev/null)
  if token_valido "$PRIMEIRO_TOKEN"; then
    # Testa contra a API para garantir que a chave do Keycloak não mudou
    HTTP_TEST=$(curl -s -o /dev/null -w "%{http_code}" \
      "$BASE_URL/api/painel/sse/agencia-0001/1?access_token=$PRIMEIRO_TOKEN" --max-time 2)
    if [ "$HTTP_TEST" != "401" ] && [ "$HTTP_TEST" != "403" ] && [ "$HTTP_TEST" != "000" ]; then
      REUSAR_TOKENS=true
    fi
  fi
fi

if [ "$REUSAR_TOKENS" == "true" ]; then
  TOKENS_OK=$(ls "$TOKEN_DIR"/agencia-* 2>/dev/null | wc -l)
  echo "Tokens existentes ainda válidos, reutilizando ($TOKENS_OK tokens)."
else
  echo "Obtendo tokens dos admins (1 por agência)..."

  TOKENS_OK=0
  TOKENS_FAIL=0
  for a in $(seq 1 $NUM_AGENCIAS); do
    AGENCIA_ID=$(printf "agencia-%04d" "$a")
    USERNAME="admin-${AGENCIA_ID}"

    TOKEN=$(curl -s -X POST "$KEYCLOAK_URL" \
      -d "client_id=$CLIENT_ID" \
      -d "grant_type=password" \
      -d "username=$USERNAME" \
      -d "password=$PASSWORD" | jq -r '.access_token')

    if [ "$TOKEN" != "null" ] && [ -n "$TOKEN" ]; then
      echo "$TOKEN" > "$TOKEN_DIR/$AGENCIA_ID"
      TOKENS_OK=$((TOKENS_OK + 1))
    else
      TOKENS_FAIL=$((TOKENS_FAIL + 1))
    fi

    if [ $((a % 50)) -eq 0 ]; then
      printf "\r       Progresso: %d/%d tokens" "$a" "$NUM_AGENCIAS"
    fi
  done

  echo ""
  echo "       Tokens obtidos: $TOKENS_OK | Falhas: $TOKENS_FAIL"
fi

if [ "$TOKENS_OK" -eq 0 ]; then
  echo "ERRO: Nenhum token obtido. Verifique se os admins foram criados (01-setup-usuarios-keycloak.sh)."
  exit 1
fi

# ─── PREFLIGHT: garante que o endpoint SSE responde ANTES de abrir N conexões ───
# Sem isto, com os serviços fora o script abria 120 curls que morriam na hora e o
# 'wait' final retornava em silêncio ("não prende a console"). Aqui falhamos alto.
#
# ATENÇÃO (SSE): não dá para usar 'curl -o /dev/null -w %{http_code}' num endpoint
# SSE — o stream NUNCA termina, então o curl bate no --max-time, é morto por timeout
# e reporta http_code=000 mesmo com o serviço saudável (falso negativo). A forma
# correta é ler apenas a LINHA DE STATUS e os HEADERS iniciais (que chegam de
# imediato) com -D (dump de headers), descartando o corpo que segue aberto.
PREFLIGHT_AGENCIA=$(printf "agencia-%04d" 1)
PREFLIGHT_TOKEN=$(cat "$TOKEN_DIR/$PREFLIGHT_AGENCIA" 2>/dev/null)
PREFLIGHT_URL="$BASE_URL/api/painel/sse/$PREFLIGHT_AGENCIA/1?access_token=$PREFLIGHT_TOKEN"

echo ""
echo "Preflight: verificando endpoint SSE em $BASE_URL ..."

# Captura os headers de resposta num arquivo temporário. O --max-time encerra o
# stream (ele não para sozinho), mas os headers já foram escritos antes disso.
#
# JANELA: o endpoint SSE pode demorar vários segundos para emitir o primeiro byte
# (o registro cria um listener JMS no Artemis ANTES de responder — medimos ~7s com
# o broker recém-reiniciado). Por isso --max-time 12, não 3-4; com janela curta o
# curl morre antes dos headers e reporta falso "sem resposta".
PF_HEADERS=$(mktemp)
curl -s -N --max-time 12 -D "$PF_HEADERS" -o /dev/null "$PREFLIGHT_URL" 2>/dev/null

# Linha de status (ex.: "HTTP/1.1 200 OK") e content-type a partir dos headers.
PF_STATUS_LINE=$(grep -i '^HTTP/' "$PF_HEADERS" | tail -1)
PF_CODE=$(echo "$PF_STATUS_LINE" | awk '{print $2}')
PF_CTYPE=$(grep -i '^content-type:' "$PF_HEADERS" | tail -1 | tr -d '\r' | awk '{print $2}')
rm -f "$PF_HEADERS"

if [ -z "$PF_CODE" ]; then
  echo "ERRO: não foi possível conectar em $BASE_URL (nenhuma resposta HTTP)." >&2
  echo "       Os serviços estão no ar? Verifique 'docker compose ps' e, se necessário, 'docker compose up -d'." >&2
  exit 1
fi
if [ "$PF_CODE" != "200" ]; then
  echo "ERRO: endpoint SSE respondeu HTTP $PF_CODE (esperado 200)." >&2
  echo "       Um 404 text/plain normalmente significa que o api-painel está fora e o Traefik" >&2
  echo "       caiu no fallback do frontend. Verifique 'docker compose ps'." >&2
  exit 1
fi
case "$PF_CTYPE" in
  text/event-stream*) : ;;  # ok — é SSE de verdade
  *)
    echo "ERRO: resposta 200 mas Content-Type='$PF_CTYPE' (esperado text/event-stream)." >&2
    echo "       Provável roteamento errado (a requisição caiu no frontend, não na API)." >&2
    exit 1 ;;
esac
echo "       OK: 200 text/event-stream."

echo ""
echo "Conectando $TOTAL_CONEXOES painéis..."
echo "Eventos aparecerão abaixo em tempo real. Ctrl+C para encerrar."
echo "────────────────────────────────────────────────────────"

# Controle de stats e cleanup
STATS_DIR=$(mktemp -d)

cleanup() {
  echo ""
  echo "Encerrando conexões..."
  kill $(jobs -p) 2>/dev/null
  wait 2>/dev/null

  # Totalização
  TOTAL_EVENTOS=$(cat "$STATS_DIR/total" 2>/dev/null || echo 0)
  CHAMANDO=$(cat "$STATS_DIR/CHAMANDO" 2>/dev/null || echo 0)
  EM_ATENDIMENTO=$(cat "$STATS_DIR/EM_ATENDIMENTO" 2>/dev/null || echo 0)
  FINALIZADO=$(cat "$STATS_DIR/FINALIZADO" 2>/dev/null || echo 0)
  AGUARDANDO=$(cat "$STATS_DIR/AGUARDANDO" 2>/dev/null || echo 0)
  OUTROS=$((TOTAL_EVENTOS - CHAMANDO - EM_ATENDIMENTO - FINALIZADO - AGUARDANDO))

  echo ""
  echo "════════════════════════════════════════"
  echo "         TOTALIZAÇÃO DE EVENTOS"
  echo "════════════════════════════════════════"
  echo "  Total de eventos:  $TOTAL_EVENTOS"
  echo "  AGUARDANDO:        $AGUARDANDO"
  echo "  CHAMANDO:          $CHAMANDO"
  echo "  EM_ATENDIMENTO:    $EM_ATENDIMENTO"
  echo "  FINALIZADO:        $FINALIZADO"
  if [ "$OUTROS" -gt 0 ]; then
    echo "  Outros:            $OUTROS"
  fi
  echo "════════════════════════════════════════"

  rm -rf "$STATS_DIR"
  exit 0
}
trap cleanup SIGINT SIGTERM

# Inicializa contadores
echo 0 > "$STATS_DIR/total"
echo 0 > "$STATS_DIR/CHAMANDO"
echo 0 > "$STATS_DIR/EM_ATENDIMENTO"
echo 0 > "$STATS_DIR/FINALIZADO"
echo 0 > "$STATS_DIR/AGUARDANDO"

# Abre conexões SSE para cada agência/painel
for a in $(seq 1 $NUM_AGENCIAS); do
  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  TOKEN_FILE="$TOKEN_DIR/$AGENCIA_ID"

  # Pula agências sem token
  [ ! -f "$TOKEN_FILE" ] && continue
  TOKEN=$(cat "$TOKEN_FILE")

  for p in $(seq 1 $NUM_PAINEIS_POR_AGENCIA); do
    curl -s -N "$BASE_URL/api/painel/sse/$AGENCIA_ID/$p?access_token=$TOKEN" | \
      while IFS= read -r line; do
        if [[ "$line" == data:* ]]; then
          TIMESTAMP=$(date '+%H:%M:%S')
          DATA="${line#data:}"
          echo "[$TIMESTAMP] $AGENCIA_ID/painel-$p → $DATA"

          # Incrementa contadores
          flock "$STATS_DIR/total.lock" bash -c "echo \$(( \$(cat '$STATS_DIR/total') + 1 )) > '$STATS_DIR/total'"
          STATUS=$(echo "$DATA" | jq -r '.status // empty' 2>/dev/null)
          if [ -n "$STATUS" ] && [ -f "$STATS_DIR/$STATUS" ]; then
            flock "$STATS_DIR/$STATUS.lock" bash -c "echo \$(( \$(cat '$STATS_DIR/$STATUS') + 1 )) > '$STATS_DIR/$STATUS'"
          fi
        fi
      done &
  done
done

echo "[$(date '+%H:%M:%S')] $TOTAL_CONEXOES painéis aguardando eventos..."
echo ""

# Mantém o script rodando até Ctrl+C
wait
