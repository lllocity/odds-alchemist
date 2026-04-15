# Step 28: NextAuth v5 + Google OAuth + middleware 実装

閲覧用 FE へのアクセスを特定の Google アカウントのみに制限します。

---

## 実装ファイル

```
frontend-viewer/
├── auth.ts          # NextAuth 設定
└── middleware.ts    # 全ルートを認証保護
```

---

## auth.ts の仕様

- プロバイダー: Google（`next-auth/providers/google`）
- `signIn` コールバックで `profile.email` と環境変数 `ALLOWED_EMAIL` を照合
- 一致しない場合は `false` を返してログインを拒否

## middleware.ts の仕様

- `auth` をミドルウェアとしてエクスポート
- `matcher`: `/api/auth/(.*)` を除く全ルートを保護
- 未認証の場合はサインインページにリダイレクト

---

## 必要な環境変数

| 変数 | 説明 |
|---|---|
| `AUTH_GOOGLE_ID` | Google OAuth クライアント ID（Step 24） |
| `AUTH_GOOGLE_SECRET` | Google OAuth クライアントシークレット（Step 24） |
| `NEXTAUTH_SECRET` | セッション暗号化キー（`openssl rand -base64 32` で生成） |
| `NEXTAUTH_URL` | ローカル: `http://localhost:3001`、本番: Vercel の URL |
| `ALLOWED_EMAIL` | アクセスを許可する Google アカウントのメールアドレス |

---

## layout.tsx への SessionProvider 追加

NextAuth v5 では `SessionProvider` を layout.tsx に追加する必要があります。

```tsx
// app/layout.tsx
import { SessionProvider } from "next-auth/react"

export default function RootLayout({ children }) {
  return (
    <html>
      <body>
        <SessionProvider>{children}</SessionProvider>
      </body>
    </html>
  )
}
```

---

## 確認

- [ ] 未ログイン状態で `localhost:3001` にアクセス → Google ログイン画面にリダイレクトされる
- [ ] `ALLOWED_EMAIL` に設定したアカウントでログイン → ページが表示される
- [ ] 別のアカウントでログイン試行 → アクセスが拒否される
- [ ] `/api/auth/*` エンドポイントは認証なしでアクセスできる

---

## 次のステップ

→ [Step 29: 閲覧用 page.tsx・コンポーネント移植](step29_viewer_page.md)
