#!/bin/sh
# Odds Alchemist 起動スクリプト
# 使い方:
#   ./start.sh          # 通常起動（バックグラウンド）
#   ./start.sh --build  # コード変更後のビルドあり起動

# バックグラウンドで Docker Compose を起動
echo "起動中..."
docker compose up -d "$@"
EXIT_CODE=$?

if [ $EXIT_CODE -ne 0 ]; then
    echo "❌ 起動失敗（終了コード: $EXIT_CODE）"
    exit $EXIT_CODE
fi

echo "✅ 起動成功"
echo "   フロントエンド: http://localhost:3000"
echo "   バックエンド:   http://localhost:8080"
echo "   停止: ./stop.sh"
