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

# Obtém tokens por agência
echo "Obtendo tokens dos admins (1 por agência)..."
TOKEN_DIR=$(mktemp -d)

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

if [ "$TOKENS_OK" -eq 0 ]; then
  echo "ERRO: Nenhum token obtido. Verifique se os admins foram criados (01-setup-usuarios-keycloak.sh)."
  rm -rf "$TOKEN_DIR"
  exit 1
fi

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

  rm -rf "$STATS_DIR" "$TOKEN_DIR"
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
