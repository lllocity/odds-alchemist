# タスク: Step 21 Docker化によるポータビリティの確保と起動のワンコマンド化

## 目的
バックエンドとフロントエンドを別々に手動起動している現状を、`docker compose up` の1コマンドで完結させる。
秘密情報（Google認証JSON・Slack Webhook URL）はイメージに含めず、ホスト側から注入する設計とする。

---

## 前提確認（実装前にチェック）

- 永続化は Google Sheets に統一済み（Step 19）。H2 / SQLite 等のローカルDBマウントは不要。
- Slack Webhook URL は `application-secret.yaml` に記載済み（Step 20）。Docker では環境変数でオーバーライドする。
- ログファイル `/tmp/odds-alchemist/app.log` はコンテナ再起動で消えるため、volume マウントが必要。

---

## 実装要件

### 1. バックエンドの Dockerfile 作成 (`/backend/Dockerfile`)

マルチステージビルドで、Gradle ビルド用イメージと JRE 実行用イメージを分離する。

```dockerfile
# Stage 1: ビルド
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon   # レイヤーキャッシュ活用
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# Stage 2: 実行
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**秘密情報の渡し方（イメージに含めない）:**
- Google 認証 JSON: `docker-compose.yml` の `volumes` でコンテナ内の固定パス（例: `/app/secrets/gcp-key.json`）にマウント
- `GOOGLE_APPLICATION_CREDENTIALS` 環境変数でそのパスを指定
- Slack Webhook URL: `SPRING_SLACK_WEBHOOK__URL` 環境変数でオーバーライド（Spring Boot の環境変数→プロパティ変換規則に従う）

### 2. フロントエンドの Dockerfile 作成 (`/frontend/Dockerfile`)

Next.js の **standalone モード** を使い、実行時に `node_modules` 全体を持ち込まないようにする。

**前提として `next.config.js`（または `next.config.ts`）に以下を追加する：**
```js
const nextConfig = {
  output: 'standalone',
};
```

```dockerfile
# Stage 1: ビルド
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN npm run build

# Stage 2: 実行（standaloneのみコピー）
FROM node:22-alpine
WORKDIR /app
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public
ENV NODE_ENV=production
EXPOSE 3000
CMD ["node", "server.js"]
```

### 3. `docker-compose.yml` の作成（ルートディレクトリ）

```yaml
services:
  backend:
    build: ./backend
    ports:
      - "8080:8080"
    env_file: .env
    volumes:
      # Google 認証 JSON のマウント（イメージに含めない）
      - ${GCP_KEY_PATH}:/app/secrets/gcp-key.json:ro
      # ログファイルの永続化（コンテナ再起動後もログが消えないよう）
      - ./logs:/tmp/odds-alchemist
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s

  frontend:
    build:
      context: ./frontend
      args:
        NEXT_PUBLIC_API_BASE_URL: http://localhost:8080
    ports:
      - "3000:3000"
    depends_on:
      backend:
        # バックエンドの healthcheck が UP になってから起動する
        condition: service_healthy
```

### 4. `.env.example` の作成（ルートディレクトリ）

`.env` は `.gitignore` に追加し、`.env.example` のみリポジトリに含める。

```dotenv
# Google サービスアカウント JSON キーのホスト側パス
GCP_KEY_PATH=/path/to/your/gcp-key.json

# Google スプレッドシート ID（application.yaml に直書きされている場合はここで上書き可）
# SPRING_ODDS_SCRAPING_SPREADSHEET__ID=your-spreadsheet-id

# Slack Webhook URL（application-secret.yaml の値を上書き）
SPRING_SLACK_WEBHOOK__URL=https://hooks.slack.com/services/xxx/yyy/zzz
SPRING_SLACK_ENABLED=true
```

`.gitignore` に以下を追加：
```
.env
logs/
```

### 5. ドキュメントの更新

`README.md` の起動手順を以下に差し替える：

```markdown
## 起動方法

1. `.env.example` をコピーして `.env` を作成し、各値を設定する
2. `docker compose up --build` を実行する
3. フロントエンド: http://localhost:3000
4. バックエンド API: http://localhost:8080
```

### 6. 初期セットアップ手順書の作成 (`docs/setup_guide.md`)

このプロジェクトを新しい環境で動かすために必要な、Google / Slack の秘密情報の入手・設定手順を `docs/setup_guide.md` として作成する。
「何が必要で、どこで取得し、どのファイルに何を書くか」が一気通貫でわかる内容にすること。

#### 記載すべき内容

**① Google Cloud サービスアカウントキー (`credentials.json`) の取得**

1. Google Cloud Console でプロジェクトを開く
2. 「IAM と管理」→「サービスアカウント」へ移動
3. 対象のサービスアカウントを選択（または新規作成）
4. 「キー」タブ →「鍵を追加」→「新しい鍵を作成」→ JSON を選択してダウンロード
5. ダウンロードしたファイルをローカルの任意の場所に保存（例: `~/secrets/odds-alchemist-key.json`）
   - **git 管理下に置かないこと**

**② Google スプレッドシート ID の確認**

- スプレッドシートの URL: `https://docs.google.com/spreadsheets/d/{スプレッドシートID}/edit`
- `d/` と `/edit` の間の文字列がスプレッドシート ID

**③ スプレッドシートへのサービスアカウントの共有設定**

- スプレッドシートを開き「共有」→ サービスアカウントのメールアドレス（`xxx@xxx.iam.gserviceaccount.com`）を「編集者」として追加
- これを忘れると Sheets API が 403 エラーになる

**④ Slack Incoming Webhook URL の取得**

1. Slack の管理画面 → 「Apps」→「Incoming Webhooks」を検索してインストール
2. 通知先チャンネルを選択し「Incoming Webhook を追加」
3. 表示された Webhook URL（`https://hooks.slack.com/services/xxx/yyy/zzz`）をコピー

**⑤ Docker のインストール（Mac）**

1. Homebrew で Docker Desktop をインストール
   ```bash
   brew install --cask docker
   ```
2. Launchpad または `/Applications/Docker.app` を起動
3. 初回の利用規約・権限ダイアログを承認
4. メニューバーの🐋アイコンが「Docker Desktop is running」になれば準備完了

> 2回目以降は Docker Desktop が起動済みであれば `docker compose up` だけでよい

**⑥ 各情報のセット先（ローカル開発 vs Docker）**

| 情報 | ローカル開発 | Docker |
|------|-------------|--------|
| `credentials.json` | `backend/src/main/resources/credentials.json` に配置 | `.env` の `GCP_KEY_PATH` にファイルパスを記載してマウント |
| スプレッドシート ID | `application-secret.yaml` の `google.sheets.spreadsheet-id` | `.env` の `SPRING_GOOGLE_SHEETS_SPREADSHEET__ID`（任意） |
| Slack Webhook URL | `application-secret.yaml` の `slack.webhook-url` | `.env` の `SPRING_SLACK_WEBHOOK__URL` |

---

## 完了条件

- [ ] `docker compose up --build` で backend・frontend の両サービスが起動する
- [ ] バックエンド healthcheck が UP になるまでフロントエンドが待機する
- [ ] Google Sheets への読み書きが Docker 環境で正常動作する（監視URL登録・復元の確認）
- [ ] Slack 通知が Docker 環境で正常動作する
- [ ] コンテナ停止・再起動後にログファイル (`./logs/`) が保持される
- [ ] `.env` が `.gitignore` に含まれており `git status` に出ない
- [ ] `docs/setup_guide.md` が作成されており、ゼロから環境を作れる手順が記載されている
