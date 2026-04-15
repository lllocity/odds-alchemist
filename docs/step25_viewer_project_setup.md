# Step 25: frontend-viewer/ プロジェクト新規作成

閲覧用 Next.js アプリの雛形を作成し、必要な依存パッケージをインストールします。

---

## ディレクトリ構成（完成形）

```
frontend-viewer/
├── app/
│   ├── layout.tsx                          # SessionProvider・フォント設定
│   ├── page.tsx                            # 閲覧メインページ
│   ├── api/
│   │   ├── auth/[...nextauth]/route.ts     # NextAuth ハンドラー
│   │   ├── alerts/route.ts                 # Alerts シート直読み
│   │   └── odds/
│   │       └── history/
│   │           ├── route.ts                # オッズ時系列
│   │           ├── urls/route.ts           # URL一覧
│   │           ├── horses/route.ts         # 馬一覧
│   │           └── alerts/route.ts         # アラート履歴（グラフ用）
│   ├── components/
│   │   ├── AlertList.tsx                   # frontend/ からコピー
│   │   └── OddsTrendChart.tsx              # frontend/ からコピー・API パス変更
│   └── types/
│       └── oddsAlert.ts                    # frontend/ からコピー
├── lib/
│   └── sheets.ts                           # Sheets API クライアント
├── auth.ts                                 # NextAuth 設定
├── middleware.ts                           # 認証保護
├── next.config.ts
├── package.json
└── tsconfig.json
```

---

## 実装手順

### 1. プロジェクト作成

```bash
cd /path/to/odds-alchemist
npx create-next-app@latest frontend-viewer \
  --typescript --tailwind --app --no-src-dir --import-alias "@/*"
```

対話形式で聞かれる場合の選択:
- TypeScript: Yes
- ESLint: Yes
- Tailwind CSS: Yes
- `src/` directory: No
- App Router: Yes
- import alias: `@/*`

### 2. 追加パッケージのインストール

```bash
cd frontend-viewer
npm install next-auth@beta googleapis recharts
```

### 3. next.config.ts の確認

Vercel デプロイは standalone 不要（Vercel がネイティブ対応のため）。
デフォルトのままで問題なし。

### 4. .env.local の作成（ローカル開発用）

```bash
# frontend-viewer/.env.local
GOOGLE_SA_KEY={"type":"service_account",...}   # Step 23 でダウンロードした JSON の内容をそのまま貼る
GOOGLE_SHEETS_SPREADSHEET_ID=your-spreadsheet-id

# NextAuth
NEXTAUTH_SECRET=開発用の適当な文字列（例: openssl rand -base64 32 の出力）
NEXTAUTH_URL=http://localhost:3001

# Google OAuth（Step 24 で取得）
AUTH_GOOGLE_ID=xxxx.apps.googleusercontent.com
AUTH_GOOGLE_SECRET=GOCSPX-xxxx

# 許可するGoogleアカウント
ALLOWED_EMAIL=your@gmail.com
```

### 5. .gitignore に .env.local を追加（すでにあれば不要）

---

## 確認

- [ ] `frontend-viewer/` ディレクトリが作成された
- [ ] `npm run dev -- --port 3001` で `localhost:3001` が起動する
- [ ] `.env.local` が `.gitignore` に含まれている

---

## 次のステップ

→ [Step 26: lib/sheets.ts 実装](step26_sheets_client.md)
