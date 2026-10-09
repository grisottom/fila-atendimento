#!/bin/bash
# 05-teste-falha-commit.sh
# Prova empírica do CENÁRIO MALIGNO do dual-write (falha de COMMIT na triagem).
#
# Diferente do 03 (que intercala triagem+atendimento e consome a fila rápido),
# este script faz APENAS triagens numa única agência e NÃO chama atendimento,
# deixando as mensagens EM REPOUSO na queue do broker. Em seguida compara:
#   - mensagens na queue agencia.<id>.fila (broker)   vs.
#   - linhas AGUARDANDO em fila_atendimento (banco)
# A diferença (broker > banco) são as MENSAGENS ÓRFÃS: publicadas antes de um
# commit que sofreu rollback — existem no broker, não existem no banco, e o
# OutboxPublisher nunca as reconcilia (não há linha para varrer).
#
# Pré-requisito: a FAULT INJECTION (commit) deve estar ATIVA no TriagemService
# (falha nos 2 primeiros itens recepcionados globalmente).
#
# Uso: ./teste/05-teste-falha-commit.sh [QUANTIDADE]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

BASE_URL="$BASE_URL_ATENDIMENTO"
CLIENT_ID="fila-atendimento"
TRIAGEM_USER="atend-triagem"
TRIAGEM_PASS="pwd"

AGENCIA_ID="agencia-0001"
QTD=${1:-5}
SERVICO="servico-basico"

cor_verde="\033[0;32m"; cor_amarelo="\033[0;33m"; cor_vermelho="\033[0;31m"; cor_reset="\033[0m"

echo "=== Teste: falha de COMMIT na triagem (mensagem órfã) ==="
echo "Agência: $AGENCIA_ID | Triagens: $QTD | Serviço: $SERVICO"
echo ""

# ─── Estado limpo: zera o banco e reinicia o broker ───────
echo "Limpando estado (banco + broker)..."
docker exec fila-postgres psql -U fila -d fila_atendimento -c \
  "DELETE FROM fila_atendimento WHERE agencia_id='$AGENCIA_ID';" > /dev/null 2>&1
docker restart fila-artemis > /dev/null 2>&1
RETRIES=0
while [ $RETRIES -lt 30 ]; do
  docker exec fila-artemis curl -sf http://localhost:8161/console > /dev/null 2>&1 && break
  RETRIES=$((RETRIES + 1)); sleep 1
done
[ $RETRIES -ge 30 ] && { echo "ERRO: Artemis não ficou healthy."; exit 1; }
echo "Estado limpo."
echo ""

# ─── Token de triagem ─────────────────────────────────────
TOKEN=$(curl -s -X POST "$KEYCLOAK_URL" \
  -d "client_id=$CLIENT_ID" -d "grant_type=password" \
  -d "username=$TRIAGEM_USER" -d "password=$TRIAGEM_PASS" | jq -r '.access_token')
if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
  echo "ERRO: não foi possível obter token de triagem."; exit 1
fi

# ─── Dispara N triagens (sem atendimento) ─────────────────
echo "Recepcionando $QTD pessoas (sem chamar atendimento)..."
TRIAGEM_OK=0; TRIAGEM_FALHA=0
for i in $(seq 1 "$QTD"); do
  CPF=$((91000000000 + i))
  RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/triagem/recepcionar" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"cpf\": $CPF, \"nomePessoa\": \"Teste $i\", \"agenciaId\": \"$AGENCIA_ID\", \"servicoId\": \"$SERVICO\"}")
  CODE=$(echo "$RESP" | tail -1)
  if [ "$CODE" == "200" ]; then
    TRIAGEM_OK=$((TRIAGEM_OK + 1))
    echo -e "  ${cor_verde}#$i -> 200${cor_reset}"
  else
    TRIAGEM_FALHA=$((TRIAGEM_FALHA + 1))
    BODY=$(echo "$RESP" | sed '$d')
    echo -e "  ${cor_vermelho}#$i -> $CODE (falha de commit esperada) $BODY${cor_reset}"
  fi
  sleep 0.1
done
echo ""

# Dá um instante para o broker contabilizar
sleep 1

# ─── Compara broker vs. banco ─────────────────────────────
QUEUE="agencia.$AGENCIA_ID.fila"
# --queueName filtra pela queue (evita depender do match num ADDRESS truncado);
# --maxColumnSize=-1 desliga o truncamento das colunas (parsing confiável).
# A linha de dados da queue tem a coluna 2 = NAME; MESSAGE_COUNT é a coluna 5.
MSGS_BROKER=$(docker exec fila-artemis sh -c \
  "/var/lib/artemis-instance/bin/artemis queue stat --user artemis --password artemis123 --queueName '$QUEUE' --maxColumnSize=-1 2>/dev/null" \
  | awk -F'|' -v q="$QUEUE" 'NR>1 && index($3,q)>0 {gsub(/ /,"",$5); print $5; exit}')
MSGS_BROKER=${MSGS_BROKER:-0}

LINHAS_BANCO=$(docker exec fila-postgres psql -U fila -d fila_atendimento -t -A -c \
  "SELECT count(*) FROM fila_atendimento WHERE agencia_id='$AGENCIA_ID' AND status='AGUARDANDO';")
LINHAS_BANCO=${LINHAS_BANCO:-0}

ORFAS=$((MSGS_BROKER - LINHAS_BANCO))

echo "════════════════════════════════════════"
echo "            RESULTADO"
echo "════════════════════════════════════════"
printf "  Triagens 200 (sucesso):   %s\n" "$TRIAGEM_OK"
printf "  Triagens falha (commit):  %s\n" "$TRIAGEM_FALHA"
printf "  Mensagens no broker:      %s   (queue %s)\n" "$MSGS_BROKER" "$QUEUE"
printf "  Linhas AGUARDANDO (banco):%s\n" "$LINHAS_BANCO"
echo "────────────────────────────────────────"
if [ "$ORFAS" -gt 0 ]; then
  echo -e "  ${cor_amarelo}MENSAGENS ÓRFÃS: $ORFAS${cor_reset}"
  echo "  (existem no broker, não existem no banco — o outbox nunca reconcilia)"
else
  echo -e "  ${cor_verde}Sem órfãs: broker e banco batem.${cor_reset}"
fi
echo "════════════════════════════════════════"
echo ""
echo "Dica: confirme o agendamento preservado (rollback não deletou) e inspecione a"
echo "      queue com: docker exec fila-artemis /var/lib/artemis-instance/bin/artemis \\"
echo "      queue stat --user artemis --password artemis123 | grep '$QUEUE'"
