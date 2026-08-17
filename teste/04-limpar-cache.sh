#!/bin/bash
# 04-limpar-cache.sh
# Limpa tokens em cache e logs de testes anteriores.
#
# Uso: ./teste/04-limpar-cache.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

echo "=== Limpeza de cache e logs ==="
echo ""

# Tokens
if [ -d "$LOG_DIR/tokens" ]; then
  COUNT=$(find "$LOG_DIR/tokens" -type f | wc -l)
  rm -rf "$LOG_DIR/tokens"
  echo "  Tokens atendimento removidos: $COUNT arquivos"
else
  echo "  Tokens atendimento: nenhum encontrado"
fi

if [ -d "$LOG_DIR/tokens-painel" ]; then
  COUNT=$(find "$LOG_DIR/tokens-painel" -type f | wc -l)
  rm -rf "$LOG_DIR/tokens-painel"
  echo "  Tokens painel removidos:      $COUNT arquivos"
else
  echo "  Tokens painel: nenhum encontrado"
fi

# Logs
LOGS_REMOVIDOS=0
if [ -d "$LOG_DIR" ]; then
  LOGS_REMOVIDOS=$(find "$LOG_DIR" -maxdepth 1 -type f \( -name "*.log" -o -name "*.tmp" -o -name "*.json" \) | wc -l)
  find "$LOG_DIR" -maxdepth 1 -type f \( -name "*.log" -o -name "*.tmp" -o -name "*.json" \) -delete
  rm -f "$LOG_DIR"/agencia-*.log
  rm -f "$LOG_DIR/ABORT"
fi
echo "  Logs removidos:               $LOGS_REMOVIDOS arquivos"

echo ""
echo "Limpeza concluída."
