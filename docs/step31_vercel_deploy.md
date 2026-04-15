# Step 31: Vercel デプロイ（手動）

閲覧用 FE を Vercel にデプロイし、外部からアクセスできるようにします。

---

## 前提条件

- GitHub にコードが push されていること（Step 25〜30 完了後）
- Vercel アカウントを持っていること（無料の Hobby プランで十分）
- Step 23・24 が完了していること（SA キー、OAuth クライアント）

---

## 手順

### 1. Vercel でプロジェクトを作成

1. [vercel.com](https://vercel.com) にログイン
2. **「Add New Project」** → GitHub リポジトリ（odds-alchemist）を選択
3. **「Root Directory」** に `frontend-viewer` を指定
4. Framework Preset: **Next.js**（自動検出されるはず）
5. **環境変数を設定**（後述）
6. **「Deploy」** をクリック

### 2. 環境変数の設定

Vercel の Project Settings → Environment Variables に以下を設定:

| 変数名 | 値 | 説明 |
|---|---|---|
| `GOOGLE_SA_KEY` | SA の JSON ファイルの内容をそのまま貼り付け | 読み取り専用 SA キー |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | スプレッドシート ID | シート識別子 |
| `AUTH_GOOGLE_ID` | OAuth クライアント ID | Step 24 で取得 |
| `AUTH_GOOGLE_SECRET` | OAuth クライアントシークレット | Step 24 で取得 |
| `NEXTAUTH_SECRET` | `openssl rand -base64 32` の出力 | セッション暗号化 |
| `NEXTAUTH_URL` | `https://<your-app>.vercel.app` | デプロイ後に確定 |
| `ALLOWED_EMAIL` | `your@gmail.com` | アクセス許可アカウント |

### 3. デプロイ完了後：OAuth リダイレクト URI を更新

1. Vercel のデプロイ URL を確認（例: `https://odds-alchemist-viewer.vercel.app`）
2. GCP Console → OAuth クライアント → 承認済みリダイレクト URI に追加:
   `https://odds-alchemist-viewer.vercel.app/api/auth/callback/google`
3. `NEXTAUTH_URL` を本番 URL に更新して再デプロイ

### 4. 動作確認

1. Vercel の URL にアクセス
2. Google ログイン画面が表示されることを確認
3. 許可アカウントでログイン → グラフ・アラートが表示されることを確認
4. 別アカウントでログイン → 拒否されることを確認

---

## カスタムドメイン（任意）

Vercel では独自ドメインを無料で設定できる。
`odds.example.com` のようなドメインを持っている場合は Vercel の Domain 設定から追加可能。
その場合は OAuth のリダイレクト URI・`NEXTAUTH_URL` もドメインに合わせて更新すること。

---

## 確認

- [ ] Vercel デプロイが成功している
- [ ] 外部ネットワーク（自宅 WiFi 以外）からアクセスして Google ログインできる
- [ ] ログイン後にグラフ・アラートが表示される
- [ ] Sheets のデータが正しく反映されている

---

## 完了

閲覧用 FE の外部公開が完了しました。
