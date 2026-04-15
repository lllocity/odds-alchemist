# Step 24: Google OAuth クライアント作成（手動）

閲覧用 FE の NextAuth が Google ログインを使うための OAuth 2.0 クライアントを作成します。

---

## 手順

### 1. OAuth 同意画面の確認

1. [Google Cloud Console](https://console.cloud.google.com/) → **「API とサービス」→「OAuth 同意画面」**
2. ユーザーの種類: **「外部」**（個人用途なのでこちらでOK）
3. アプリ名・サポートメールを設定して保存
4. **「テストユーザー」** に自分のGoogleアカウントを追加しておく
   （公開前の段階では登録ユーザーのみログイン可）

### 2. OAuth 2.0 クライアント ID 作成

1. **「API とサービス」→「認証情報」→「認証情報を作成」→「OAuth クライアント ID」**
2. アプリケーションの種類: **「ウェブ アプリケーション」**
3. 名前: `odds-viewer`
4. **「承認済みのリダイレクト URI」** に以下を追加:
   - ローカル開発用: `http://localhost:3001/api/auth/callback/google`
   - Vercel 本番用: `https://<your-vercel-domain>/api/auth/callback/google`
     （Vercel デプロイ後に URL が確定したら追加）
5. **「作成」** をクリック
6. **クライアント ID** と **クライアント シークレット** を控える

---

## 控えておく値

```
GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-xxxx
```

これらは Step 28 の NextAuth 設定と Step 31 の Vercel 環境変数で使用します。

---

## 確認

- [ ] OAuth 同意画面が設定されている
- [ ] テストユーザーに自分のアカウントが追加されている
- [ ] クライアント ID とシークレットを控えた

---

## 次のステップ

→ [Step 25: frontend-viewer プロジェクト新規作成](step25_viewer_project_setup.md)
