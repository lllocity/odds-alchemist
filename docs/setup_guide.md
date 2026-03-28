# 初期セットアップ手順書

このプロジェクトを新しい環境でゼロから動かすために必要な手順をまとめたガイドです。

---

## 必要なもの

| 情報 | 用途 |
|------|------|
| Google サービスアカウント JSON キー | Sheets API へのアクセス認証 |
| Google スプレッドシート ID | データ保存先 |
| Slack Incoming Webhook URL | アラート通知 |

---

## ① Docker Desktop のインストール（Mac）

```bash
brew install --cask docker
```

インストール後、Launchpad または `/Applications/Docker.app` を起動し、初回の利用規約・権限ダイアログを承認する。メニューバーの🐋アイコンが「Docker Desktop is running」になれば準備完了。

---

## ② Google Cloud サービスアカウントキーの取得

1. [Google Cloud Console](https://console.cloud.google.com/) でプロジェクトを開く
2. 「IAM と管理」→「サービスアカウント」へ移動
3. 対象のサービスアカウントを選択（または新規作成）
4. 「キー」タブ →「鍵を追加」→「新しい鍵を作成」→ JSON を選択してダウンロード
5. ダウンロードしたファイルをローカルの任意の場所に保存

   例: `~/secrets/odds-alchemist-key.json`

   > **注意**: git 管理下（プロジェクトディレクトリ内）には置かないこと

---

## ③ Google スプレッドシート ID の確認

スプレッドシードの URL を確認する。

```
https://docs.google.com/spreadsheets/d/{スプレッドシートID}/edit
```

`d/` と `/edit` の間の文字列が**スプレッドシート ID**。

---

## ④ スプレッドシートへのサービスアカウントの共有設定

1. スプレッドシートを開き「共有」ボタンをクリック
2. サービスアカウントのメールアドレス（`xxx@xxx.iam.gserviceaccount.com`）を入力
3. 権限を「編集者」に設定して追加

> これを忘れると Sheets API が 403 エラーになる

---

## ⑤ Slack Incoming Webhook URL の取得

1. Slack の管理画面 →「Apps」→「Incoming Webhooks」を検索してインストール
2. 通知先チャンネルを選択し「Incoming Webhook を追加」
3. 表示された Webhook URL をコピー

   例: `https://hooks.slack.com/services/xxx/yyy/zzz`

---

## ⑥ `.env` ファイルの作成

プロジェクトルートで以下を実行:

```bash
cp .env.example .env
```

`.env` を開き、以下の値を設定する:

```dotenv
# ② で保存した JSON キーの絶対パス
GCP_KEY_PATH=/Users/yourname/secrets/odds-alchemist-key.json

# ③ で確認したスプレッドシート ID
GOOGLE_SHEETS_SPREADSHEET_ID=your-spreadsheet-id

# ⑤ でコピーした Slack Webhook URL
SLACK_WEBHOOK__URL=https://hooks.slack.com/services/xxx/yyy/zzz
SLACK_ENABLED=true
```

> `.env` は `.gitignore` に含まれているため、git にコミットされない

---

## ⑦ 起動

```bash
docker compose up --build -d
```

- フロントエンド: http://localhost:3000
- バックエンド API: http://localhost:8081

---

## ⑦-B LAN 越しアクセス（別端末のブラウザから操作する場合）

同じ WiFi ネットワーク上の別端末（例: MacBook のブラウザ）から iMac 上の Docker にアクセスしたい場合は、以下の設定が必要。

### 1. iMac の IP アドレスを確認する

```bash
ipconfig getifaddr en0
# 例: 192.168.1.10
```

または「システム設定 → Wi-Fi → 詳細 → TCP/IP」で確認。

### 2. `.env` に API ベース URL を設定する

```dotenv
NEXT_PUBLIC_API_BASE_URL=http://192.168.1.10:8081
```

> `192.168.1.10` は実際の iMac の IP に置き換えること

### 3. 再ビルドして起動する

```bash
docker compose up --build -d
```

`NEXT_PUBLIC_API_BASE_URL` は Next.js のビルド時に JS へ焼き込まれるため、変更後は必ず `--build` で再ビルドが必要。

### 4. MacBook から接続する

MacBook のブラウザで `http://192.168.1.10:3000` にアクセスする。

> **補足**: CORS は `192.168.*.*` の全アドレスを許可済み。IP レンジが `10.x.x.x` や `172.16.x.x` などの場合はコード内の `originPatterns` への追加が必要。

---

## ⑧ 日常的な操作

```bash
# ログを確認する
docker compose logs -f           # 全サービス
docker compose logs -f backend   # バックエンドのみ
docker compose logs -f frontend  # フロントエンドのみ

# 停止
docker compose down
```

---

## 各情報のセット先まとめ

| 情報 | ローカル開発 | Docker |
|------|-------------|--------|
| `credentials.json` | `backend/src/main/resources/credentials.json` に配置 | `.env` の `GCP_KEY_PATH` にファイルパスを記載してマウント |
| スプレッドシート ID | `application-secret.yaml` の `google.sheets.spreadsheet-id` | `.env` の `GOOGLE_SHEETS_SPREADSHEET_ID` |
| Slack Webhook URL | `application-secret.yaml` の `slack.webhook-url` | `.env` の `SLACK_WEBHOOK__URL` |
