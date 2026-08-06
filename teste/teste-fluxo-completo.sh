#!/bin/bash
# teste-fluxo-completo.sh
# Teste de carga: 1200 agências, 250k agendamentos, 5 atendentes simultâneos por agência.
# Execução PARALELA com controle de concorrência.

set -e

# ─── CONFIGURAÇÃO ─────────────────────────────────────────
NUM_AGENCIAS=1200
NUM_PAINEIS_POR_AGENCIA=1
NUM_AGENDAMENTOS=2         # por agência (simula janela de 5 min: ~2400 total)
NUM_ATENDENTES_POR_AGENCIA=2
MAX_PARALELO=200           # máximo de agências processando simultaneamente
BASE_URL="http://localhost:3001"
KEYCLOAK_URL="http://localhost:8080/realms/fila-atendimento/protocol/openid-connect/token"
CLIENT_ID="fila-atendimento"

# Usuários
TRIAGEM_USER="atend-triagem"
TRIAGEM_PASS="pwd"
ATEND_PASS="pwd"

# Serviços disponíveis
SERVICOS=("servico-basico" "servico-normal-01" "servico-normal-02" "servico-especial-01")

DELAY=0.02

# Diretório para logs
LOG_DIR="/tmp/teste-fila-logs"
ERROS_FILE="$LOG_DIR/erros.log"
mkdir -p "$LOG_DIR"
> "$ERROS_FILE"

# ─── FUNÇÕES AUXILIARES ───────────────────────────────────

cor_verde="\033[0;32m"
cor_amarelo="\033[0;33m"
cor_azul="\033[0;34m"
cor_vermelho="\033[0;31m"
cor_reset="\033[0m"

log_info()  { echo -e "${cor_azul}[INFO]${cor_reset} $1"; }
log_ok()    { echo -e "${cor_verde}[ OK ]${cor_reset} $1"; }
log_warn()  { echo -e "${cor_amarelo}[WARN]${cor_reset} $1"; }
log_erro()  { echo -e "${cor_vermelho}[ERRO]${cor_reset} $1"; }

obter_token() {
  local user=$1
  local pass=$2
  local token
  token=$(curl -s -X POST "$KEYCLOAK_URL" \
    -d "client_id=$CLIENT_ID" \
    -d "grant_type=password" \
    -d "username=$user" \
    -d "password=$pass" | jq -r '.access_token')
  if [ "$token" == "null" ] || [ -z "$token" ]; then
    return 1
  fi
  echo "$token"
}

gerar_cpf() {
  printf "%011d" $((90000000000 + $1))
}

# Controle de concorrência: limita processos em paralelo
semaforo_aguardar() {
  while [ $(jobs -rp | wc -l) -ge $MAX_PARALELO ]; do
    sleep 0.5
  done
}

# ─── FUNÇÃO: PROCESSAR UMA AGÊNCIA ───────────────────────
processar_agencia() {
  local a=$1
  local TOKEN_TRIAGEM=$2
  shift 2
  local TOKENS_ATEND=("$@")

  local AGENCIA_ID
  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  local LOG_FILE="$LOG_DIR/$AGENCIA_ID.log"

  local NOMES=("Ana" "Beatriz" "Carlos" "Daniel" "Eduardo" "Fernanda"
    "Gabriel" "Helena" "Igor" "Juliana" "Karen" "Lucas" "Marina"
    "Nelson" "Olivia" "Paulo" "Raquel" "Samuel" "Tatiana" "Ulisses")
  local SOBRENOMES=("Silva" "Santos" "Oliveira" "Souza" "Lima"
    "Costa" "Pereira" "Almeida" "Rocha" "Ferreira")

  # Busca estações
  local ESTACOES_CSV
  ESTACOES_CSV=$(docker exec fila-postgres psql -U fila -d fila_atendimento -t -A -c \
    "SELECT id FROM estacao WHERE agencia_id = '$AGENCIA_ID' AND tipo_estacao = 'GUICHE' AND numero_estacao >= 101 ORDER BY numero_estacao LIMIT $NUM_PAINEIS_POR_AGENCIA;")
  local ESTACOES
  IFS=$'\n' read -rd '' -a ESTACOES <<< "$ESTACOES_CSV" || true

  if [ ${#ESTACOES[@]} -eq 0 ]; then
    echo "0 0 0 0" > "$LOG_FILE"
    return 1
  fi

  local TRIAGEM_SUCESSO=0 TRIAGEM_FALHA=0
  local ATEND_SUCESSO=0 ATEND_FALHA=0
  local OFFSET=$(( (a - 1) * NUM_AGENDAMENTOS ))
  local IDS_FILE="$LOG_DIR/${AGENCIA_ID}-all-ids.tmp"
  > "$IDS_FILE"

  # ─── FLUXO INTERCALADO: triagem → atendimento (1 a 1) ───
  for i in $(seq 1 $NUM_AGENDAMENTOS); do
    # 1. Triagem: recepciona 1 pessoa
    local CPF
    CPF=$(gerar_cpf $((OFFSET + i)))
    local SERVICO=${SERVICOS[$((RANDOM % ${#SERVICOS[@]}))]}
    local NOME="${NOMES[$((RANDOM % ${#NOMES[@]}))]}"
    local SOBRENOME="${SOBRENOMES[$((RANDOM % ${#SOBRENOMES[@]}))]}"
    local NOME_COMPLETO="$NOME $SOBRENOME"

    local RESPONSE HTTP_CODE
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/triagem/recepcionar" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TOKEN_TRIAGEM" \
      -d "{\"cpf\": $CPF, \"nomePessoa\": \"$NOME_COMPLETO\", \"agenciaId\": \"$AGENCIA_ID\", \"servicoId\": \"$SERVICO\"}")

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)

    if [ "$HTTP_CODE" == "200" ]; then
      TRIAGEM_SUCESSO=$((TRIAGEM_SUCESSO + 1))
    else
      TRIAGEM_FALHA=$((TRIAGEM_FALHA + 1))
      local BODY=$(echo "$RESPONSE" | sed '$d')
      echo "[$AGENCIA_ID] TRIAGEM #$i HTTP=$HTTP_CODE RESP=$BODY" >> "$ERROS_FILE"
      continue
    fi

    sleep 0.2

    # 2. Atendimento: atendentes disputam o item que acabou de entrar
    local ATENDIDO=false
    local ATEND_IDX=$(( (i - 1) % NUM_ATENDENTES_POR_AGENCIA ))
    local TOKEN_ATEND=${TOKENS_ATEND[$ATEND_IDX]}
    local ESTACAO_ID=${ESTACOES[$(( ATEND_IDX % ${#ESTACOES[@]} ))]}
    local RETRIES=0

    while [ "$ATENDIDO" == "false" ] && [ $RETRIES -lt 15 ]; do
      RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/atendimento/chamar" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN_ATEND" \
        -d "{\"estacaoId\": $ESTACAO_ID}")

      HTTP_CODE=$(echo "$RESPONSE" | tail -1)
      local BODY=$(echo "$RESPONSE" | sed '$d')

      if [ "$HTTP_CODE" != "200" ]; then
        if echo "$BODY" | grep -q "Nenhum atendimento na fila"; then
          RETRIES=$((RETRIES + 1))
          sleep 0.3
          continue
        fi
        ATEND_FALHA=$((ATEND_FALHA + 1))
        echo "[$AGENCIA_ID] ATEND #$i HTTP=$HTTP_CODE BODY=$BODY" >> "$ERROS_FILE"
        break
      fi

      local ID_FILA=$(echo "$BODY" | jq -r '.id')
      echo "$ID_FILA" >> "$IDS_FILE"

      # Iniciar
      curl -s -X POST "$BASE_URL/api/atendimento/iniciar/$ID_FILA" \
        -H "Authorization: Bearer $TOKEN_ATEND" > /dev/null
      sleep "$DELAY"

      # Finalizar
      curl -s -X POST "$BASE_URL/api/atendimento/finalizar/$ID_FILA" \
        -H "Authorization: Bearer $TOKEN_ATEND" > /dev/null

      ATEND_SUCESSO=$((ATEND_SUCESSO + 1))
      ATENDIDO=true
    done

    if [ "$ATENDIDO" == "false" ] && [ $RETRIES -ge 15 ]; then
      echo "[$AGENCIA_ID] ATEND #$i FILA_VAZIA retries=$RETRIES" >> "$ERROS_FILE"
    fi

    sleep "$DELAY"
  done

  echo -e "${cor_verde}[$AGENCIA_ID]${cor_reset} Triagem: $TRIAGEM_SUCESSO | Atendimento: $ATEND_SUCESSO/$NUM_AGENDAMENTOS"

  # Detecta duplicatas
  local TOTAL_IDS=$(wc -l < "$IDS_FILE" 2>/dev/null || echo 0)
  local UNIQUE_IDS=$(sort -u "$IDS_FILE" 2>/dev/null | wc -l || echo 0)
  local ESPERADO=$NUM_AGENDAMENTOS

  if [ "$ATEND_SUCESSO" -ne "$ESPERADO" ] || [ "$TOTAL_IDS" -gt "$UNIQUE_IDS" ]; then
    local MSG="[$AGENCIA_ID] ESPERADO=$ESPERADO FINALIZADOS=$ATEND_SUCESSO IDS=$TOTAL_IDS UNICOS=$UNIQUE_IDS"

    if [ "$TOTAL_IDS" -gt "$UNIQUE_IDS" ] && [ "$UNIQUE_IDS" -gt 0 ]; then
      local DUPLICADOS=$(sort "$IDS_FILE" | uniq -d | tr '\n' ',' | sed 's/,$//')
      MSG+=" DUPLICADOS=[$DUPLICADOS]"
      echo -e "${cor_vermelho}[ERRO] $MSG${cor_reset}"
      for dup_id in $(sort "$IDS_FILE" | uniq -d); do
        local DB_INFO=$(docker exec fila-postgres psql -U fila -d fila_atendimento -t -A -c \
          "SELECT id, senha, status, atendente_username, version FROM fila_atendimento WHERE id = $dup_id;" 2>/dev/null)
        echo -e "${cor_vermelho}       DB: $DB_INFO${cor_reset}"
      done
      touch "$LOG_DIR/ABORT"
    fi

    echo "$MSG" >> "$ERROS_FILE"
  fi

  rm -f "$IDS_FILE"
  echo "$TRIAGEM_SUCESSO $TRIAGEM_FALHA $ATEND_SUCESSO $ATEND_FALHA" > "$LOG_FILE"
}

# ─── INÍCIO ───────────────────────────────────────────────

TOTAL_PAINEIS=$((NUM_AGENCIAS * NUM_PAINEIS_POR_AGENCIA))
TOTAL_ATENDIMENTOS=$((NUM_AGENCIAS * NUM_AGENDAMENTOS))
TOTAL_ATENDENTES=$((NUM_AGENCIAS * NUM_ATENDENTES_POR_AGENCIA))

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║       TESTE DE CARGA - FILA ATENDIMENTO                    ║"
echo "║    (PARALELO + ATENDENTES SIMULTÂNEOS + CONCORRÊNCIA)      ║"
echo "╠══════════════════════════════════════════════════════════════╣"
printf "║  Agências:        %-6d                                   ║\n" "$NUM_AGENCIAS"
printf "║  Painéis:         %-6d                                   ║\n" "$TOTAL_PAINEIS"
printf "║  Atendentes:      %-6d (%d por agência)                  ║\n" "$TOTAL_ATENDENTES" "$NUM_ATENDENTES_POR_AGENCIA"
printf "║  Atendimentos:    %-6d (%d por agência)                  ║\n" "$TOTAL_ATENDIMENTOS" "$NUM_AGENDAMENTOS"
printf "║  Concorrência:    %-6d agências simultâneas              ║\n" "$MAX_PARALELO"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# ─── LIMPEZA DA FILA (execuções anteriores) ───────────────
log_info "Limpando fila de atendimentos anteriores..."
docker exec fila-postgres psql -U fila -d fila_atendimento -c "DELETE FROM fila_atendimento;" > /dev/null 2>&1
rm -f "$LOG_DIR"/*.tmp "$LOG_DIR"/agencia-*.log "$LOG_DIR/ABORT"
> "$ERROS_FILE"
log_ok "Fila e logs limpos."

# ─── PASSO 0: CRIAR AGÊNCIAS, PAINÉIS E ESTAÇÕES ─────────
log_info "Criando infraestrutura no banco (agências, painéis, estações)..."

# Usa SQL diretamente com DO block para eficiência
SQL_INFRA="
DO \$\$
DECLARE
  a INTEGER;
  p INTEGER;
  p_id INTEGER;
  agencia_var VARCHAR(50);
BEGIN
  FOR a IN 1..$NUM_AGENCIAS LOOP
    agencia_var := 'agencia-' || LPAD(a::text, 4, '0');
    INSERT INTO agencia (id, nome)
    VALUES (agencia_var, 'Agência ' || a || ' - Teste')
    ON CONFLICT (id) DO NOTHING;

    FOR p IN 1..$NUM_PAINEIS_POR_AGENCIA LOOP
      INSERT INTO painel (agencia_id, numero_painel, localizacao)
      VALUES (agencia_var, p, 'Teste - Painel ' || p)
      ON CONFLICT (agencia_id, numero_painel) DO NOTHING;

      SELECT id INTO p_id FROM painel
      WHERE agencia_id = agencia_var AND numero_painel = p;

      INSERT INTO estacao (agencia_id, tipo_estacao, numero_estacao, localizacao, painel_id)
      VALUES (agencia_var, 'GUICHE', 100 + p, 'Teste - Guichê ' || p, p_id)
      ON CONFLICT (agencia_id, tipo_estacao, numero_estacao) DO NOTHING;
    END LOOP;
  END LOOP;
END\$\$;"

docker exec fila-postgres psql -U fila -d fila_atendimento -c "$SQL_INFRA" > /dev/null 2>&1
log_ok "Infraestrutura criada."

# ─── PASSO 0b: INSERIR PESSOAS NO BANCO (em batches) ─────
log_info "Inserindo $TOTAL_ATENDIMENTOS pessoas no banco (transação única)..."

NOMES=("Ana" "Beatriz" "Carlos" "Daniel" "Eduardo" "Fernanda"
  "Gabriel" "Helena" "Igor" "Juliana" "Karen" "Lucas" "Marina"
  "Nelson" "Olivia" "Paulo" "Raquel" "Samuel" "Tatiana" "Ulisses")
SOBRENOMES=("Silva" "Santos" "Oliveira" "Souza" "Lima"
  "Costa" "Pereira" "Almeida" "Rocha" "Ferreira")

TOTAL_PESSOAS=$TOTAL_ATENDIMENTOS
SQL_FILE="/tmp/teste-fila-pessoas.sql"
echo "BEGIN;" > "$SQL_FILE"
for i in $(seq 1 $TOTAL_PESSOAS); do
  CPF=$(gerar_cpf $i)
  NOME="${NOMES[$((RANDOM % ${#NOMES[@]}))]}"
  SOBRENOME="${SOBRENOMES[$((RANDOM % ${#SOBRENOMES[@]}))]}"
  echo "INSERT INTO pessoa (cpf, nome) VALUES ($CPF, '$NOME $SOBRENOME') ON CONFLICT (cpf) DO NOTHING;" >> "$SQL_FILE"
done
echo "COMMIT;" >> "$SQL_FILE"

docker cp "$SQL_FILE" fila-postgres:/tmp/pessoas.sql
docker exec fila-postgres psql -U fila -d fila_atendimento -f /tmp/pessoas.sql > /dev/null 2>&1
rm -f "$SQL_FILE"
log_ok "$TOTAL_PESSOAS pessoas inseridas."

# ─── PASSO 1: OBTER TOKENS (com cache) ───────────────────
TOKENS_DIR="$LOG_DIR/tokens"
mkdir -p "$TOKENS_DIR"

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
if [ -f "$TOKENS_DIR/agencia-0001.tokens" ] && [ -f "$TOKENS_DIR/triagem.token" ]; then
  PRIMEIRO_TOKEN=$(head -1 "$TOKENS_DIR/agencia-0001.tokens" 2>/dev/null)
  if token_valido "$PRIMEIRO_TOKEN"; then
    TOKEN_TRIAGEM=$(cat "$TOKENS_DIR/triagem.token")
    if token_valido "$TOKEN_TRIAGEM"; then
      REUSAR_TOKENS=true
    fi
  fi
fi

if [ "$REUSAR_TOKENS" == "true" ]; then
  log_ok "Tokens existentes ainda válidos, reutilizando."
else
  log_info "Obtendo tokens ($TOTAL_ATENDENTES atendentes + 1 triagem) em paralelo..."

  TOKEN_TRIAGEM=$(obter_token "$TRIAGEM_USER" "$TRIAGEM_PASS" || true)
  if [ -z "$TOKEN_TRIAGEM" ]; then
    log_erro "Falha token triagem"
    exit 1
  fi
  echo "$TOKEN_TRIAGEM" > "$TOKENS_DIR/triagem.token"

  MAX_TOKEN_PARALELO=100

  obter_tokens_agencia() {
    local a=$1
    local AGENCIA_ID
    AGENCIA_ID=$(printf "agencia-%04d" "$a")
    local TOKEN_FILE="$TOKENS_DIR/$AGENCIA_ID.tokens"
    > "$TOKEN_FILE"
    for n in $(seq 1 $NUM_ATENDENTES_POR_AGENCIA); do
      local USERNAME="atend-${AGENCIA_ID}-${n}"
      local TOKEN
      TOKEN=$(curl -s -X POST "$KEYCLOAK_URL" \
        -d "client_id=$CLIENT_ID" \
        -d "grant_type=password" \
        -d "username=$USERNAME" \
        -d "password=$ATEND_PASS" | jq -r '.access_token')
      if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
        TOKEN=$(curl -s -X POST "$KEYCLOAK_URL" \
          -d "client_id=$CLIENT_ID" \
          -d "grant_type=password" \
          -d "username=atend-all" \
          -d "password=$ATEND_PASS" | jq -r '.access_token')
      fi
      echo "$TOKEN" >> "$TOKEN_FILE"
    done
  }
  export -f obter_tokens_agencia
  export KEYCLOAK_URL CLIENT_ID ATEND_PASS NUM_ATENDENTES_POR_AGENCIA TOKENS_DIR

  for a in $(seq 1 $NUM_AGENCIAS); do
    while [ $(jobs -rp | wc -l) -ge $MAX_TOKEN_PARALELO ]; do
      sleep 0.1
    done
    obter_tokens_agencia "$a" &
    if [ $((a % 100)) -eq 0 ]; then
      printf "\r  Tokens: disparadas %d/%d agências" "$a" "$NUM_AGENCIAS"
    fi
  done
  wait
  echo ""

  TOKENS_OBTIDOS=$(cat "$TOKENS_DIR"/agencia-*.tokens 2>/dev/null | grep -c . || echo 0)
  log_ok "$TOKENS_OBTIDOS tokens obtidos."
fi
echo ""

# ─── PASSO 2: EXECUTAR AGÊNCIAS EM PARALELO ──────────────
log_info "Iniciando processamento ($NUM_AGENCIAS agências, max $MAX_PARALELO simultâneas)..."
echo ""

INICIO=$(date +%s)

export NUM_AGENDAMENTOS NUM_PAINEIS_POR_AGENCIA NUM_ATENDENTES_POR_AGENCIA
export BASE_URL DELAY LOG_DIR ERROS_FILE
export -f processar_agencia gerar_cpf

CONCLUIDAS=0
for a in $(seq 1 $NUM_AGENCIAS); do
  # Verifica se alguma agência detectou duplicata
  if [ -f "$LOG_DIR/ABORT" ]; then
    echo ""
    log_erro "ABORTANDO: duplicata detectada. Encerrando processos..."
    kill $(jobs -rp) 2>/dev/null
    wait 2>/dev/null
    rm -f "$LOG_DIR/ABORT"
    exit 1
  fi

  semaforo_aguardar

  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  TOKEN_FILE="$TOKENS_DIR/$AGENCIA_ID.tokens"
  mapfile -t TOKENS_ARRAY < "$TOKEN_FILE"

  processar_agencia "$a" "$TOKEN_TRIAGEM" "${TOKENS_ARRAY[@]}" &

  CONCLUIDAS=$((CONCLUIDAS + 1))
  if [ $((CONCLUIDAS % 100)) -eq 0 ]; then
    printf "\r  Agências disparadas: %d/%d" "$CONCLUIDAS" "$NUM_AGENCIAS"
  fi
done

echo ""
log_info "Aguardando conclusão de todas as agências..."
wait

# Verifica abort final (pode ter sido sinalizado por agências tardias)
if [ -f "$LOG_DIR/ABORT" ]; then
  log_erro "Duplicata detectada durante execução. Verifique os logs acima."
  rm -f "$LOG_DIR/ABORT"
  exit 1
fi

FIM=$(date +%s)
DURACAO=$((FIM - INICIO))
echo ""

# ─── RESUMO FINAL ────────────────────────────────────────
TRIAGEM_SUCESSO_TOTAL=0
TRIAGEM_FALHA_TOTAL=0
ATEND_SUCESSO_TOTAL=0
ATEND_FALHA_TOTAL=0

for a in $(seq 1 $NUM_AGENCIAS); do
  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  LOG_FILE="$LOG_DIR/$AGENCIA_ID.log"
  if [ -f "$LOG_FILE" ]; then
    read TS TF AS AF < "$LOG_FILE"
    TRIAGEM_SUCESSO_TOTAL=$((TRIAGEM_SUCESSO_TOTAL + ${TS:-0}))
    TRIAGEM_FALHA_TOTAL=$((TRIAGEM_FALHA_TOTAL + ${TF:-0}))
    ATEND_SUCESSO_TOTAL=$((ATEND_SUCESSO_TOTAL + ${AS:-0}))
    ATEND_FALHA_TOTAL=$((ATEND_FALHA_TOTAL + ${AF:-0}))
  fi
done

TOTAL_FALHAS=$((TRIAGEM_FALHA_TOTAL + ATEND_FALHA_TOTAL))

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                      RESUMO FINAL                          ║"
echo "╠══════════════════════════════════════════════════════════════╣"
printf "║  Agências:      %-6d (max %d paralelas)                  ║\n" "$NUM_AGENCIAS" "$MAX_PARALELO"
printf "║  Atendentes:    %-6d (%d simultâneos/agência)            ║\n" "$TOTAL_ATENDENTES" "$NUM_ATENDENTES_POR_AGENCIA"
printf "║  Triagem:       %-6d recepcionados                       ║\n" "$TRIAGEM_SUCESSO_TOTAL"
printf "║  Atendimento:   %-6d finalizados                         ║\n" "$ATEND_SUCESSO_TOTAL"
printf "║  Falhas:        %-6d                                     ║\n" "$TOTAL_FALHAS"
printf "║  Duração:       %-6d segundos                            ║\n" "$DURACAO"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# ─── ERROS DETALHADOS (máximo 10) ────────────────────────
TOTAL_ERROS=$(wc -l < "$ERROS_FILE" 2>/dev/null || echo 0)
if [ "$TOTAL_ERROS" -gt 0 ]; then
  echo -e "${cor_vermelho}─── ERROS (mostrando até 10 de $TOTAL_ERROS) ───${cor_reset}"
  head -10 "$ERROS_FILE" | while IFS= read -r linha; do
    echo -e "  ${cor_vermelho}✗${cor_reset} $linha"
  done
  if [ "$TOTAL_ERROS" -gt 10 ]; then
    echo -e "  ... e mais $((TOTAL_ERROS - 10)) erros em: $ERROS_FILE"
  fi
  echo ""
fi
