#!/bin/bash
# importar-usuarios-keycloak.sh
# Importa usuários de teste no Keycloak via Admin REST API em paralelo.
#
# Pré-requisitos:
#   - Keycloak rodando (docker compose up)
#   - Arquivo teste/usuarios-teste.json gerado pelo gerar-usuarios-keycloak.sh
#   - jq e curl instalados
#
# Uso: ./teste/importar-usuarios-keycloak.sh [PARALLELISM]
#   PARALLELISM: número de requests simultâneos (padrão: 10)

set -e

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="fila-atendimento"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
INPUT="teste/usuarios-teste.json"
PARALLELISM=${1:-10}

if [ ! -f "$INPUT" ]; then
  echo "Erro: arquivo $INPUT não encontrado."
  echo "Execute primeiro: ./teste/gerar-usuarios-keycloak.sh"
  exit 1
fi

echo "Importando usuários no Keycloak ($KEYCLOAK_URL)"
echo "  Realm: $REALM"
echo "  Arquivo: $INPUT"
echo "  Paralelismo: $PARALLELISM"
echo ""

# Obtém token de admin
echo "Obtendo token de admin..."
TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=$ADMIN_USER" \
  -d "password=$ADMIN_PASS" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r '.access_token')

if [ "$TOKEN" = "null" ] || [ -z "$TOKEN" ]; then
  echo "Erro: não foi possível obter token de admin. Verifique se o Keycloak está rodando."
  exit 1
fi
echo "Token obtido."

# Busca IDs das realm roles para atribuição posterior
echo "Buscando roles do realm..."
ROLES_JSON=$(curl -s "$KEYCLOAK_URL/admin/realms/$REALM/roles" \
  -H "Authorization: Bearer $TOKEN")

get_role_json() {
  echo "$ROLES_JSON" | jq -c "[.[] | select(.name == \"basica\" or .name == \"normal\" or .name == \"especial\")]"
}
ROLE_MAPPINGS=$(get_role_json)
echo "Roles encontradas: $(echo "$ROLE_MAPPINGS" | jq length)"

TOTAL=$(jq length "$INPUT")
echo ""
echo "Iniciando importação de $TOTAL usuários..."

# Arquivo temporário para controle de progresso
PROGRESS_DIR=$(mktemp -d)
trap "rm -rf $PROGRESS_DIR" EXIT

# Função para criar um usuário e atribuir roles
create_user() {
  local idx=$1
  local user_json=$2
  local username=$(echo "$user_json" | jq -r '.username')

  # Monta o payload (sem realmRoles, que é atribuído separadamente)
  local payload=$(echo "$user_json" | jq '{
    username: .username,
    enabled: .enabled,
    firstName: .firstName,
    lastName: .lastName,
    email: .email,
    emailVerified: .emailVerified,
    attributes: .attributes,
    credentials: .credentials
  }')

  # Cria o usuário
  local http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$payload")

  if [ "$http_code" = "201" ]; then
    # Busca o ID do usuário criado
    local user_id=$(curl -s "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$username&exact=true" \
      -H "Authorization: Bearer $TOKEN" | jq -r '.[0].id')

    # Atribui roles
    if [ "$user_id" != "null" ] && [ -n "$user_id" ]; then
      curl -s -o /dev/null -X POST \
        "$KEYCLOAK_URL/admin/realms/$REALM/users/$user_id/role-mappings/realm" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$ROLE_MAPPINGS"
    fi
    touch "$PROGRESS_DIR/ok_$idx"
  elif [ "$http_code" = "409" ]; then
    # Usuário já existe — ok
    touch "$PROGRESS_DIR/skip_$idx"
  else
    echo "$username:$http_code" >> "$PROGRESS_DIR/errors.log"
  fi
}

export -f create_user
export KEYCLOAK_URL REALM TOKEN ROLE_MAPPINGS PROGRESS_DIR

# Executa em paralelo usando xargs
seq 0 $((TOTAL - 1)) | xargs -P "$PARALLELISM" -I {} bash -c '
  user_json=$(jq -c ".[$1]" "'"$INPUT"'")
  create_user "$1" "$user_json"
' _ {}

# Relatório final
OK_COUNT=$(ls "$PROGRESS_DIR"/ok_* 2>/dev/null | wc -l)
SKIP_COUNT=$(ls "$PROGRESS_DIR"/skip_* 2>/dev/null | wc -l)
ERROR_COUNT=0
if [ -f "$PROGRESS_DIR/errors.log" ]; then
  ERROR_COUNT=$(wc -l < "$PROGRESS_DIR/errors.log")
fi

echo ""
echo "Importação concluída:"
echo "  Criados: $OK_COUNT"
echo "  Já existiam: $SKIP_COUNT"
echo "  Erros: $ERROR_COUNT"

if [ "$ERROR_COUNT" -gt 0 ]; then
  echo ""
  echo "Primeiros erros:"
  head -5 "$PROGRESS_DIR/errors.log"
fi
