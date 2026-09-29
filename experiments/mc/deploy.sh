#!/bin/bash
# Сборка и выкладка плагина на сервер с перезапуском.
# Пароль пользователя mc берётся из переменной MC_PASS.
set -euo pipefail
cd "$(dirname "$0")"

HOST=mc@37.153.70.33
: "${MC_PASS:?задайте MC_PASS=<пароль mc>}"
export SSHPASS="$MC_PASS"

gradle build -q --console=plain
JAR=$(ls build/libs/VladCore-*.jar)

sshpass -e ssh "$HOST" 'rm -f ~/server/plugins/VladCore-*.jar'
sshpass -e scp "$JAR" "$HOST:server/plugins/"
sshpass -e ssh "$HOST" '
  ~/mc.sh stop
  while screen -list | grep -q "\.mc\s"; do sleep 1; done
  START=$(date +%s)
  ~/mc.sh start
  # Ждём именно новый лог (старый latest.log тоже содержит "Done (").
  for i in $(seq 1 90); do
    sleep 2
    [ "$(stat -c %Y ~/server/logs/latest.log 2>/dev/null || echo 0)" -ge "$START" ] \
      && grep -q "Done (" ~/server/logs/latest.log && break
  done
  grep -E "VladCore|ERROR|Exception" ~/server/logs/latest.log | tail -20
'
