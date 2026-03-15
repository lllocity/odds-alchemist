#!/bin/sh
# Mac のスリープを抑制しながら Docker Compose を起動する
# 使い方:
#   ./start.sh up --build   # 初回 / コード変更時
#   ./start.sh up           # 2回目以降
#   ./start.sh down         # 停止
caffeinate -i docker compose "$@"
