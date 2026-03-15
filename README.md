# Odds Alchemist

JRA（日本中央競馬会）のオッズ情報を定期的に取得・分析し、投資妙味のある異常オッズをリアルタイムで検知・通知するシステム。

> **免責事項**: 本システムは個人的な学習・研究目的で開発したものです。不特定多数での利用は想定していません。対象サービスの利用規約を遵守し、自己責任でご利用ください。

## 機能概要

- **オッズ取得**: Yahoo!スポナビ競馬の公開オッズページから情報を収集
- **異常検知**: 以下の3種類の異常を自動検知
  - **支持率急増**: 前回比 +2.0%以上の急激な人気集中
  - **順位乖離**: 単勝と複勝の人気順位が3つ以上ずれている馬
  - **トレンド逸脱**: その日の初回取得時から支持率が +5.0%以上変化した中穴・大穴馬（5〜12番人気）
- **永続化**: 取得データとアラートを Google Sheets に記録
- **スケジュール監視**: 発走時刻に応じてポーリング間隔を自動調整（30分 / 15分 / 5分 / 1分）

## 技術スタック

| レイヤー | 技術 |
|---|---|
| バックエンド | Java 21, Spring Boot 4.0.x |
| フロントエンド | Next.js 14+ (App Router), TypeScript, Tailwind CSS |
| HTML解析 | Jsoup |
| 永続化 | Google Sheets API |

## 起動方法

### Docker（推奨）

**前提条件**: Docker Desktop（`brew install --cask docker` でインストール）

1. `.env` を作成して秘密情報を設定する

   ```bash
   cp .env.example .env
   # .env を編集（詳細は docs/setup_guide.md を参照）
   ```

2. Docker Desktop を起動する

3. 以下のコマンドを実行する

   ```bash
   ./start.sh up --build   # 初回 / コード変更時
   ./start.sh up           # 2回目以降
   ```

   > `start.sh` は `caffeinate -i` と組み合わせており、実行中は Mac のスリープを抑制する。

4. アクセス先
   - フロントエンド: http://localhost:3000
   - バックエンド API: http://localhost:8080

### 運用コマンド

```bash
# ターミナルをバックグラウンドに切り離す（コンテナは動き続ける）
# 起動後に表示されるメニューで d キーを押す

# ログを再表示する
docker compose logs -f           # 全サービス
docker compose logs -f backend   # バックエンドのみ
docker compose logs -f frontend  # フロントエンドのみ

# 停止（コンテナを削除）
./start.sh down

# 再起動（ビルド済みイメージを再利用）
./start.sh up
```

### ローカル開発（Docker なし）

**前提条件**: Java 21、Node.js 18+、Google Cloud 認証情報

1. `backend/src/main/resources/application-secret.yaml` を作成:

   ```yaml
   google:
     sheets:
       spreadsheet-id: "YOUR_SPREADSHEET_ID"
       credentials-path: "/path/to/credentials.json"
   slack:
     webhook-url: "https://hooks.slack.com/services/xxx/yyy/zzz"
   ```

2. 起動:

   ```bash
   # バックエンド
   cd backend && ./gradlew bootRun

   # フロントエンド（別ターミナル）
   cd frontend && npm install && npm run dev
   ```

ブラウザで `http://localhost:3000` にアクセス。

## 使い方

1. フロントエンドの「監視URL登録」フォームに Yahoo!スポナビ競馬のオッズページURLを入力して登録する
   - 例: `https://sports.yahoo.co.jp/keiba/race/odds/tfw/2606020211`
   - 登録と同時に初回オッズ取得・異常検知が即時（非同期）で実行される
2. 以降はスケジューラーが自動的に定期取得・異常検知を継続する（発走時刻に応じて間隔を自動調整）
3. 検知されたアラートはトップページのアラート一覧にリアルタイム表示される
4. 「即時取得」フォームから任意のURLを指定してワンショット取得も可能

## Google Sheets の構成

| シート名 | 用途 | 列 |
|---|---|---|
| `OddsData` | 取得したオッズデータ | A:取得日時 / B:URL / C:レース名 / D:馬番 / E:馬名 / F:単勝 / G:複勝下限 / H:複勝上限 |
| `Alerts` | 検知アラート | A:検知日時 / B:URL / C:レース名 / D:馬番 / E:馬名 / F:検知タイプ / G:数値 |

## API エンドポイント

| メソッド | パス | 説明 |
|---|---|---|
| `POST` | `/api/odds/fetch` | 指定URLのオッズを即時取得・検知 |
| `GET` | `/api/odds/alerts` | 最新のアラート一覧を取得 |
| `GET` | `/api/odds/targets` | 登録済み監視URL一覧を取得 |
| `POST` | `/api/odds/targets` | 監視URLを追加（登録直後に即時fetch実行） |
| `DELETE` | `/api/odds/targets` | 監視URLを削除 |

## 本番運用（Mac でのスケジュール監視）

`./gradlew bootRun` によるフォアグラウンド起動は開発用途向けで、Mac がスリープするとJVMが一時停止しスケジューラーが止まります。
レース監視を安定して動かすには以下の手順を使用してください。

### 1. JAR をビルド

```bash
cd backend
./gradlew bootJar
# → backend/build/libs/backend-0.0.1-SNAPSHOT.jar が生成される
```

### 2. スリープ抑制しながらバックグラウンド起動

```bash
caffeinate -i nohup java -jar backend/build/libs/backend-0.0.1-SNAPSHOT.jar \
  > /dev/null 2>&1 &
echo "PID: $!"  # プロセスIDを控えておく
```

- `caffeinate -i`：起動中はMacのスリープを抑制する
- `nohup ... &`：ターミナルを閉じてもプロセスを継続する
- ログは `/tmp/odds-alchemist/app.log` に出力される（起動ディレクトリに依存しない絶対パス）

### 3. ログの確認

```bash
tail -f /tmp/odds-alchemist/app.log
```

### 4. 停止

```bash
# 起動時に表示されたPIDで停止
kill <PID>

# PIDを忘れた場合
lsof -ti:8080 | xargs kill
```

### 起動方法の比較

| 方法 | スリープ耐性 | 用途 |
|---|---|---|
| `./gradlew bootRun` | なし | 開発のみ |
| `nohup java -jar &` | なし（スリープで停止） | 簡易バックグラウンド |
| `caffeinate -i` + `java -jar` | あり | レース当日の本番監視 |

> **注意**: `caffeinate` はプロセスが動いている間、Mac のシステムスリープを抑制します。バッテリー駆動時は電源アダプタの接続を推奨します。

## 開発

```bash
# バックエンドテスト
cd backend
./gradlew test

# フロントエンドビルド確認
cd frontend
npm run build
```
