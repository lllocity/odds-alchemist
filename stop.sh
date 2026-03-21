#!/bin/sh
# Odds Alchemist 停止スクリプト

CAFFEINATE_PID_FILE=".caffeinate.pid"

docker compose down

# caffeinate を終了
if [ -f "$CAFFEINATE_PID_FILE" ]; then
    kill "$(cat "$CAFFEINATE_PID_FILE")" 2>/dev/null
    rm "$CAFFEINATE_PID_FILE"
    echo "スリープ抑制を解除しました"
fi
