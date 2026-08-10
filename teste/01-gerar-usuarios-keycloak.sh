#!/bin/bash
# gerar-usuarios-keycloak.sh
# Gera um arquivo JSON com atendentes de teste para importação via Admin API.
# Usa hashIterations baixo (1000) para acelerar a geração e importação em dev.
#
# Uso: ./teste/gerar-usuarios-keycloak.sh
# Resultado: teste/usuarios-teste.json
#
# Para importar no Keycloak: ./teste/importar-usuarios-keycloak.sh

set -e

NUM_AGENCIAS=${1:-120}
NUM_ATENDENTES_POR_AGENCIA=2
OUTPUT="teste/usuarios-teste.json"

TOTAL=$((NUM_AGENCIAS * NUM_ATENDENTES_POR_AGENCIA))
echo "Gerando $TOTAL usuários para $NUM_AGENCIAS agências..."
echo "(hashIterations=1000 para dev — não usar em produção)"
echo ""

# Pré-calcula hash de "pwd" com PBKDF2-SHA256 (iterações baixas para dev)
read HASH_VALUE HASH_SALT <<< $(python3 -c "
import hashlib, base64, os
salt = os.urandom(16)
dk = hashlib.pbkdf2_hmac('sha256', b'pwd', salt, 1000, dklen=32)
print(base64.b64encode(dk).decode(), base64.b64encode(salt).decode())
")

SECRET_DATA="{\\\"value\\\":\\\"$HASH_VALUE\\\",\\\"salt\\\":\\\"$HASH_SALT\\\"}"
CREDENTIAL_DATA="{\\\"hashIterations\\\":1000,\\\"algorithm\\\":\\\"pbkdf2-sha256\\\"}"

# Gera o JSON como array de usuários
echo "[" > "$OUTPUT"

PRIMEIRO=1
for a in $(seq 1 $NUM_AGENCIAS); do
  AGENCIA_ID=$(printf "agencia-%04d" "$a")
  for n in $(seq 1 $NUM_ATENDENTES_POR_AGENCIA); do
    USERNAME="atend-${AGENCIA_ID}-${n}"

    if [ $PRIMEIRO -eq 1 ]; then
      PRIMEIRO=0
    else
      echo "," >> "$OUTPUT"
    fi

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
    "realmRoles": ["basica", "normal", "especial"]
  }
EOF
  done

  if [ $((a % 100)) -eq 0 ]; then
    printf "\r  Progresso: %d/%d agências" "$a" "$NUM_AGENCIAS"
  fi
done

echo "" >> "$OUTPUT"
echo "]" >> "$OUTPUT"

echo ""
TAMANHO=$(du -h "$OUTPUT" | cut -f1)
echo "Arquivo gerado: $OUTPUT ($TAMANHO, $TOTAL usuários)"
echo ""
echo "Para importar no Keycloak:"
echo "  ./teste/importar-usuarios-keycloak.sh"
