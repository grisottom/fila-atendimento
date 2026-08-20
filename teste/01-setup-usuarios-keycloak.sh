#!/bin/bash
# setup-usuarios-keycloak.sh
# Gera e importa atendentes de teste no Keycloak (role: atendente) e no banco de dados
# (tabelas: atendente + permissoes_atendente).
# Idempotente: pode rodar múltiplas vezes sem duplicar usuários.
#
# Uso: ./teste/01-setup-usuarios-keycloak.sh [NUM_AGENCIAS] [PARALLELISM]

set -e

# Garante execução relativa à raiz do projeto
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

source "$SCRIPT_DIR/config.sh"

NUM_AGENCIAS=${1:-$NUM_AGENCIAS}
PARALLELISM=${2:-10}
OUTPUT="$LOG_DIR/usuarios-teste.json"
mkdir -p "$LOG_DIR"

KEYCLOAK_BASE_URL="http://localhost:8080"
REALM="fila-atendimento"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

TOTAL=$((NUM_AGENCIAS * NUM_ATENDENTES_POR_AGENCIA))
TOTAL_ADMINS=$NUM_AGENCIAS

echo "╔══════════════════════════════════════════╗"
echo "║  Setup Usuários Keycloak + Banco (idem) ║"
echo "╠══════════════════════════════════════════╣"
printf "║  Agências:    %-6d                   ║\n" "$NUM_AGENCIAS"
printf "║  Admins:      %-6d (1/agência)       ║\n" "$TOTAL_ADMINS"
printf "║  Atendentes:  %-6d (%d/agência)      ║\n" "$TOTAL" "$NUM_ATENDENTES_POR_AGENCIA"
printf "║  Paralelismo: %-6d                   ║\n" "$PARALLELISM"
echo "╚══════════════════════════════════════════╝"
echo ""

# ─── FASE 1: GERAR JSON ──────────────────────────────────
echo "[1/3] Gerando JSON com $TOTAL_ADMINS admins + $TOTAL atendentes (hashIterations=1000 para dev)..."

read HASH_VALUE HASH_SALT <<< $(python3 -c "
import hashlib, base64, os
salt = os.urandom(16)
dk = hashlib.pbkdf2_hmac('sha256', b'pwd', salt, 1000, dklen=32)
print(base64.b64encode(dk).decode(), base64.b64encode(salt).decode())
")

SECRET_DATA="{\\\"value\\\":\\\"$HASH_VALUE\\\",\\\"salt\\\":\\\"$HASH_SALT\\\"}"
CREDENTIAL_DATA="{\\\"hashIterations\\\":1000,\\\"algorithm\\\":\\\"pbkdf2-sha256\\\"}"

echo "[" > "$OUTPUT"

PRIMEIRO=1
for a in $(seq 1 $NUM_AGENCIAS); do
  AGENCIA_ID=$(printf "agencia-%04d" "$a")

  # Admin da agência
  USERNAME="admin-${AGENCIA_ID}"
  if [ $PRIMEIRO -eq 1 ]; then
    PRIMEIRO=0
  else
    echo "," >> "$OUTPUT"
  fi

  cat >> "$OUTPUT" << EOF
  {
    "username": "$USERNAME",
    "enabled": true,
    "firstName": "Admin",
    "lastName": "$AGENCIA_ID",
    "email": "${USERNAME}@teste.local",
    "emailVerified": true,
    "credentials": [{
      "type": "password",
      "secretData": "$SECRET_DATA",
      "credentialData": "$CREDENTIAL_DATA"
    }],
    "attributes": {"agencia": ["$AGENCIA_ID"]},
    "realmRoles": ["admin"]
  }
EOF

  # Atendentes da agência
  for n in $(seq 1 $NUM_ATENDENTES_POR_AGENCIA); do
    USERNAME="atend-${AGENCIA_ID}-${n}"
    echo "," >> "$OUTPUT"

    cat >> "$OUTPUT" << EOF
  {
    "username": "$USERNAME",
    "enabled": true,
    "firstName": "Atendente $n",
    "lastName": "$AGENCIA_ID",
    "email": "${USERNAME}@teste.local",
    "emailVerified": true,
    "credentials": [{
      "type": "password",
      "secretData": "$SECRET_DATA",
      "credentialData": "$CREDENTIAL_DATA"
    }],
    "attributes": {"agencia": ["$AGENCIA_ID"]},
    "realmRoles": ["atendente"]
  }
EOF
  done

  if [ $((a % 100)) -eq 0 ]; then
    printf "\r       Progresso: %d/%d agências" "$a" "$NUM_AGENCIAS"
  fi
done

echo "" >> "$OUTPUT"
echo "]" >> "$OUTPUT"
echo ""

TAMANHO=$(du -h "$OUTPUT" | cut -f1)
echo "       JSON gerado: $OUTPUT ($TAMANHO)"
echo ""

# ─── FASE 2: IMPORTAR NO KEYCLOAK ────────────────────────
echo "[2/3] Importando no Keycloak ($KEYCLOAK_BASE_URL)..."

# Obtém token de admin
TOKEN=$(curl -s -X POST "$KEYCLOAK_BASE_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$ADMIN_USER" \
  -d "password=$ADMIN_PASS" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r '.access_token')

if [ "$TOKEN" = "null" ] || [ -z "$TOKEN" ]; then
  echo "Erro: não foi possível obter token de admin. Keycloak rodando?"
  exit 1
fi

# Registra atributo 'agencia' no User Profile (necessário no KC 24 para aceitar via API)
echo "       Registrando atributo 'agencia' no User Profile..."
UP_CONFIG=$(curl -s "$KEYCLOAK_BASE_URL/admin/realms/$REALM/users/profile" \
  -H "Authorization: Bearer $TOKEN")

HAS_AGENCIA=$(echo "$UP_CONFIG" | jq '[.attributes[] | select(.name == "agencia")] | length')
if [ "$HAS_AGENCIA" -eq 0 ]; then
  UPDATED_CONFIG=$(echo "$UP_CONFIG" | jq '.attributes += [{
    "name": "agencia",
    "displayName": "Agência",
    "permissions": {"view": ["admin", "user"], "edit": ["admin"]},
    "validations": {"length": {"max": 50}}
  }]')
  curl -s -o /dev/null -w "\n%{http_code}" \
    -X PUT "$KEYCLOAK_BASE_URL/admin/realms/$REALM/users/profile" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$UPDATED_CONFIG"
  echo "       Atributo 'agencia' registrado."
else
  echo "       Atributo 'agencia' já existe no User Profile."
fi

# Busca roles para atribuição
ROLES_JSON=$(curl -s "$KEYCLOAK_BASE_URL/admin/realms/$REALM/roles" \
  -H "Authorization: Bearer $TOKEN")
ROLE_ATENDENTE=$(echo "$ROLES_JSON" | jq -c '[.[] | select(.name == "atendente")]')
ROLE_ADMIN=$(echo "$ROLES_JSON" | jq -c '[.[] | select(.name == "admin")]')

echo "       Role 'atendente': $(echo "$ROLE_ATENDENTE" | jq length) encontrada(s)"
echo "       Role 'admin': $(echo "$ROLE_ADMIN" | jq length) encontrada(s)"
if [ "$(echo "$ROLE_ATENDENTE" | jq length)" -eq 0 ] || [ "$(echo "$ROLE_ADMIN" | jq length)" -eq 0 ]; then
  echo "ERRO: Roles 'atendente' e/ou 'admin' não encontradas no realm."
  exit 1
fi

# Controle de progresso
PROGRESS_DIR=$(mktemp -d)
trap "rm -rf $PROGRESS_DIR" EXIT

create_user() {
  local idx=$1
  local user_json=$2
  local username=$(echo "$user_json" | jq -r '.username')
  local role=$(echo "$user_json" | jq -r '.realmRoles[0]')

  local payload=$(echo "$user_json" | jq '{
    username, enabled, firstName, lastName, email, emailVerified, attributes, credentials
  }')

  local http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$KEYCLOAK_BASE_URL/admin/realms/$REALM/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$payload")

  if [ "$http_code" = "201" ]; then
    local user_id=$(curl -s "$KEYCLOAK_BASE_URL/admin/realms/$REALM/users?username=$username&exact=true" \
      -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')
    if [ "$user_id" != "null" ] && [ -n "$user_id" ]; then
      local role_mapping="$ROLE_ATENDENTE"
      if [ "$role" = "admin" ]; then
        role_mapping="$ROLE_ADMIN"
      fi
      curl -s -o /dev/null -X POST \
        "$KEYCLOAK_BASE_URL/admin/realms/$REALM/users/$user_id/role-mappings/realm" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$role_mapping"
    fi
    touch "$PROGRESS_DIR/ok_$idx"
  elif [ "$http_code" = "409" ]; then
    touch "$PROGRESS_DIR/skip_$idx"
  else
    echo "$username:$http_code" >> "$PROGRESS_DIR/errors.log"
  fi
}

export -f create_user
export KEYCLOAK_BASE_URL REALM TOKEN ROLE_ATENDENTE ROLE_ADMIN PROGRESS_DIR

TOTAL_USERS=$((TOTAL + TOTAL_ADMINS))
seq 0 $((TOTAL_USERS - 1)) | xargs -P "$PARALLELISM" -I {} bash -c '
  user_json=$(jq -c ".[{}]" "'"$OUTPUT"'")
  create_user "{}" "$user_json"
' _

# Relatório Keycloak
OK_COUNT=$(ls "$PROGRESS_DIR"/ok_* 2>/dev/null | wc -l)
SKIP_COUNT=$(ls "$PROGRESS_DIR"/skip_* 2>/dev/null | wc -l)
ERROR_COUNT=0
if [ -f "$PROGRESS_DIR/errors.log" ]; then
  ERROR_COUNT=$(wc -l < "$PROGRESS_DIR/errors.log")
fi

echo ""
echo "       Criados:      $OK_COUNT"
echo "       Já existiam:  $SKIP_COUNT"
echo "       Erros:        $ERROR_COUNT"

if [ "$ERROR_COUNT" -gt 0 ]; then
  echo ""
  echo "       Primeiros erros:"
  head -5 "$PROGRESS_DIR/errors.log" | sed 's/^/         /'
fi

echo ""

# ─── FASE 3: INSERIR PERMISSÕES NO BANCO ─────────────────
echo "[3/3] Inserindo atendentes e permissões no banco de dados..."

SQL_FILE="/tmp/teste-fila-atendentes.sql"
echo "BEGIN;" > "$SQL_FILE"

for a in $(seq 1 $NUM_AGENCIAS); do
  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  for n in $(seq 1 $NUM_ATENDENTES_POR_AGENCIA); do
    USERNAME="atend-${AGENCIA_ID}-${n}"

    # Insere atendente (idempotente)
    echo "INSERT INTO atendente (username, agencia_id) VALUES ('$USERNAME', '$AGENCIA_ID') ON CONFLICT (username, agencia_id) DO NOTHING;" >> "$SQL_FILE"

    # Insere permissões (basica, normal, especial) para todos os atendentes de teste
    echo "INSERT INTO permissoes_atendente (atendente_id, permissao) SELECT id, 'basica' FROM atendente WHERE username = '$USERNAME' AND agencia_id = '$AGENCIA_ID' ON CONFLICT (atendente_id, permissao) DO NOTHING;" >> "$SQL_FILE"
    echo "INSERT INTO permissoes_atendente (atendente_id, permissao) SELECT id, 'normal' FROM atendente WHERE username = '$USERNAME' AND agencia_id = '$AGENCIA_ID' ON CONFLICT (atendente_id, permissao) DO NOTHING;" >> "$SQL_FILE"
    echo "INSERT INTO permissoes_atendente (atendente_id, permissao) SELECT id, 'especial' FROM atendente WHERE username = '$USERNAME' AND agencia_id = '$AGENCIA_ID' ON CONFLICT (atendente_id, permissao) DO NOTHING;" >> "$SQL_FILE"
  done
done

echo "COMMIT;" >> "$SQL_FILE"

docker cp "$SQL_FILE" fila-postgres:/tmp/atendentes.sql
docker exec fila-postgres psql -U fila -d fila_atendimento -f /tmp/atendentes.sql > /dev/null 2>&1
rm -f "$SQL_FILE"

ATENDENTES_CRIADOS=$(docker exec fila-postgres psql -U fila -d fila_atendimento -t -A -c \
  "SELECT COUNT(*) FROM atendente WHERE username LIKE 'atend-agencia-%';")
PERMISSOES_CRIADAS=$(docker exec fila-postgres psql -U fila -d fila_atendimento -t -A -c \
  "SELECT COUNT(*) FROM permissoes_atendente pa JOIN atendente a ON pa.atendente_id = a.id WHERE a.username LIKE 'atend-agencia-%';")

echo "       Atendentes no banco:  $ATENDENTES_CRIADOS"
echo "       Permissões no banco:  $PERMISSOES_CRIADAS"
echo ""
echo "Setup concluído."
