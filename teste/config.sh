#!/bin/bash
# Configuração comum aos scripts de teste.
# Importado via: source "$(dirname "$0")/config.sh"

NUM_AGENCIAS=120
NUM_PAINEIS_POR_AGENCIA=1
NUM_ATENDENTES_POR_AGENCIA=2
NUM_AGENDAMENTOS_AGENCIA=2

# URLs
BASE_URL_PAINEL="http://localhost:3000"
BASE_URL_ATENDIMENTO="http://localhost:3001"
KEYCLOAK_URL="http://localhost:8080/realms/fila-atendimento/protocol/openid-connect/token"

# Diretório de artefatos de teste (tokens, logs, erros, json gerado)
LOG_DIR="/tmp/teste-fila-logs"

# Concorrência
MAX_PARALELO=40
