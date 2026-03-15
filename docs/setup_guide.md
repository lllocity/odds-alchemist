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

# ⑤ でコピーした Slack Webhook URL
SPRING_SLACK_WEBHOOK__URL=https://hooks.slack.com/services/xxx/yyy/zzz
SPRING_SLACK_ENABLED=true
```

> `.env` は `.gitignore` に含まれているため、git にコミットされない

---

## ⑦ 起動

```bash
./start.sh up --build   # 初回 / コード変更時
./start.sh up           # 2回目以降
```

- フロントエンド: http://localhost:3000
- バックエンド API: http://localhost:8080

バックエンドの起動が完了するまで（約30秒）フロントエンドは自動で待機する。`start.sh` は `caffeinate -i` と組み合わせており、実行中は Mac のスリープを抑制する。

---

## ⑧ 日常的な操作

```bash
# ターミナルをバックグラウンドに切り離す（コンテナは動き続ける）
# 起動後に表示されるメニューで d キーを押す

# ログを再表示する
docker compose logs -f           # 全サービス
docker compose logs -f backend   # バックエンドのみ
docker compose logs -f frontend  # フロントエンドのみ

# 停止（コンテナを削除）
./start.sh down

# 再起動（ビルド済みイメージを再利用、コード変更なしの場合）
./start.sh up
```

---

## 各情報のセット先まとめ

| 情報 | ローカル開発 | Docker |
|------|-------------|--------|
| `credentials.json` | `backend/src/main/resources/credentials.json` に配置 | `.env` の `GCP_KEY_PATH` にファイルパスを記載してマウント |
| スプレッドシート ID | `application-secret.yaml` の `google.sheets.spreadsheet-id` | `.env` の `SPRING_ODDS_SCRAPING_SPREADSHEET__ID`（任意） |
| Slack Webhook URL | `application-secret.yaml` の `slack.webhook-url` | `.env` の `SPRING_SLACK_WEBHOOK__URL` |
