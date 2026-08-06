#!/bin/bash
# gerar-usuarios-keycloak.sh
# Gera atendentes de teste com credenciais pré-hashadas para evitar
# timeout na importação do Keycloak (hash de 2400 senhas é muito lento).
#
# Uso: ./teste/gerar-usuarios-keycloak.sh
# Resultado:
#   - infra/keycloak/realm-export-teste.json (usuários de teste)
#   - infra/keycloak/realm-combined.json (arquivo final para o Keycloak)

set -e

NUM_AGENCIAS=1200
NUM_ATENDENTES_POR_AGENCIA=2
REALM_BASE="infra/keycloak/realm-export.json"
REALM_EXTRA="infra/keycloak/realm-export-teste.json"
REALM_COMBINED="infra/keycloak/realm-combined.json"

TOTAL=$((NUM_AGENCIAS * NUM_ATENDENTES_POR_AGENCIA))
echo "Gerando $TOTAL usuários para $NUM_AGENCIAS agências..."
echo "(credenciais pré-hashadas — sem overhead na importação)"
echo ""

# Pré-calcula hash de "pwd" com PBKDF2-SHA256 (formato Keycloak interno)
read HASH_VALUE HASH_SALT <<< $(python3 -c "
import hashlib, base64, os
salt = os.urandom(16)
dk = hashlib.pbkdf2_hmac('sha256', b'pwd', salt, 27500, dklen=32)
print(base64.b64encode(dk).decode(), base64.b64encode(salt).decode())
")

# secretData e credentialData no formato JSON que o Keycloak espera
SECRET_DATA="{\\\"value\\\":\\\"$HASH_VALUE\\\",\\\"salt\\\":\\\"$HASH_SALT\\\"}"
CREDENTIAL_DATA="{\\\"hashIterations\\\":27500,\\\"algorithm\\\":\\\"pbkdf2-sha256\\\"}"

cat > "$REALM_EXTRA" << 'HEADER'
{
  "users": [
HEADER

PRIMEIRO=1
for a in $(seq 1 $NUM_AGENCIAS); do
  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  for n in $(seq 1 $NUM_ATENDENTES_POR_AGENCIA); do
    USERNAME="atend-${AGENCIA_ID}-${n}"

    if [ $PRIMEIRO -eq 1 ]; then
      PRIMEIRO=0
    else
      echo "," >> "$REALM_EXTRA"
    fi

    cat >> "$REALM_EXTRA" << EOF
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
      "realmRoles": ["basica", "normal", "especial"]
    }
EOF
  done

  if [ $((a % 100)) -eq 0 ]; then
    printf "\r  Progresso: %d/%d agências" "$a" "$NUM_AGENCIAS"
  fi
done

cat >> "$REALM_EXTRA" << 'FOOTER'

  ]
}
FOOTER
echo ""

# ─── Combina base + extra ─────────────────────────────────
echo "Combinando $REALM_BASE + $REALM_EXTRA..."
jq --slurpfile extra "$REALM_EXTRA" '.users += $extra[0].users' "$REALM_BASE" > "$REALM_COMBINED"

TOTAL_USERS=$(jq '.users | length' "$REALM_COMBINED")
TAMANHO=$(du -h "$REALM_COMBINED" | cut -f1)
echo "✓ Arquivo combinado: $REALM_COMBINED ($TAMANHO, $TOTAL_USERS usuários)"
echo ""
echo "Recrie o Keycloak para aplicar:"
echo "  docker compose up -d --force-recreate keycloak"
