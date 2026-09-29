#!/bin/bash
# Запуск переходника BuildAI в фоне (если ещё не запущен). Лог — proxy.log рядом.
cd "$(dirname "$0")"
if [ ! -s key ]; then
  echo "Нет ключа: положите его в $(pwd)/key (echo 'КЛЮЧ' > key; chmod 600 key)"
  exit 1
fi
pgrep -f "node .*buildai-proxy/proxy.js" >/dev/null && { echo "уже запущен"; exit 0; }
nohup node "$(pwd)/proxy.js" >> proxy.log 2>&1 < /dev/null &
echo "запущен"
