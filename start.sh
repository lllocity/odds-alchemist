#!/bin/sh
# Odds Alchemist 起動スクリプト
# 使い方:
#   ./start.sh          # 通常起動（バックグラウンド）
#   ./start.sh --build  # コード変更後のビルドあり起動

CAFFEINATE_PID_FILE=".caffeinate.pid"

# 既存の caffeinate があれば終了
if [ -f "$CAFFEINATE_PID_FILE" ]; then
    kill "$(cat "$CAFFEINATE_PID_FILE")" 2>/dev/null
    rm "$CAFFEINATE_PID_FILE"
fi

# バックグラウンドで Docker Compose を起動
echo "起動中..."
docker compose up -d "$@"
EXIT_CODE=$?

if [ $EXIT_CODE -ne 0 ]; then
    echo "❌ 起動失敗（終了コード: $EXIT_CODE）"
    exit $EXIT_CODE
fi

# スリープ抑制をバックグラウンドで開始
caffeinate -i &
echo $! > "$CAFFEINATE_PID_FILE"

echo "✅ 起動成功（スリープ抑制中）"
echo "   フロントエンド: http://localhost:3000"
echo "   バックエンド:   http://localhost:8080"
echo "   停止: ./stop.sh"
