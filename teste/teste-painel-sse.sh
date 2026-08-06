#!/bin/bash
# teste-massivo-sse.sh
# Simula conexões SSE para múltiplas agências e painéis sem usar navegador.
# Eventos são exibidos em TEMPO REAL no terminal conforme chegam.

NUM_AGENCIAS=1200
NUM_PAINEIS_POR_AGENCIA=1
BASE_URL="http://localhost:3000"
KEYCLOAK_URL="http://localhost:8080/realms/fila-atendimento/protocol/openid-connect/token"
CLIENT_ID="fila-painel"
USERNAME="ger"
PASSWORD="pwd"

TOTAL_CONEXOES=$((NUM_AGENCIAS * NUM_PAINEIS_POR_AGENCIA))

echo "=== Teste Massivo SSE (tempo real) ==="
echo "Agências: $NUM_AGENCIAS"
echo "Painéis por agência: $NUM_PAINEIS_POR_AGENCIA"
echo "Total de conexões SSE: $TOTAL_CONEXOES"
echo ""

# Obtém token do Keycloak
echo "Obtendo token do Keycloak..."
TOKEN=$(curl -s -X POST "$KEYCLOAK_URL" \
  -d "client_id=$CLIENT_ID" \
  -d "grant_type=password" \
  -d "username=$USERNAME" \
  -d "password=$PASSWORD" | jq -r '.access_token')

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
  echo "ERRO: Não foi possível obter token. Verifique as credenciais e se o Keycloak está rodando."
  exit 1
fi

echo "Token obtido com sucesso."
echo ""
echo "Conectando $TOTAL_CONEXOES painéis..."
echo "Eventos aparecerão abaixo em tempo real. Ctrl+C para encerrar."
echo "────────────────────────────────────────────────────────"

# Limpa processos ao sair (Ctrl+C ou kill)
cleanup() {
  echo ""
  echo "Encerrando conexões..."
  kill $(jobs -p) 2>/dev/null
  wait 2>/dev/null
  echo "Todos encerrados."
  exit 0
}
trap cleanup SIGINT SIGTERM

# Abre conexões SSE para cada agência/painel
for a in $(seq 1 $NUM_AGENCIAS); do
  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  for p in $(seq 1 $NUM_PAINEIS_POR_AGENCIA); do
    curl -s -N "$BASE_URL/api/painel/sse/$AGENCIA_ID/$p?access_token=$TOKEN" | \
      while IFS= read -r line; do
        if [[ "$line" == data:* ]]; then
          TIMESTAMP=$(date '+%H:%M:%S')
          DATA="${line#data:}"
          echo "[$TIMESTAMP] $AGENCIA_ID/painel-$p → $DATA"
        fi
      done &
  done
done

echo "[$(date '+%H:%M:%S')] $TOTAL_CONEXOES painéis aguardando eventos..."
echo ""

# Mantém o script rodando até Ctrl+C
wait
