# Step 23: GCP 読み取り専用サービスアカウント作成（手動）

閲覧用 FE (frontend-viewer) が Google Sheets を直接読み取るための、
書き込み権限を持たない専用サービスアカウントを作成します。

---

## 手順

### 1. サービスアカウント作成

1. [Google Cloud Console](https://console.cloud.google.com/) を開く
2. 既存のプロジェクト（odds-alchemist 用）を選択
3. **「IAM と管理」→「サービスアカウント」** を開く
4. **「サービスアカウントを作成」** をクリック
5. 以下を入力:
   - 名前: `odds-viewer-readonly`
   - 説明: `閲覧用FE (Vercel) からの Sheets 読み取り専用アクセス`
6. **「作成して続行」** をクリック
7. ロール付与は **スキップ**（Sheets の共有設定で権限付与するため）
8. **「完了」** をクリック

### 2. JSON キーの発行

1. 作成したサービスアカウントをクリック
2. **「キー」タブ** → **「鍵を追加」→「新しい鍵を作成」**
3. 形式: **JSON** → **「作成」**
4. JSON ファイルがダウンロードされる → 安全な場所に保管

### 3. スプレッドシートへの共有追加

1. 対象の Google スプレッドシートを開く
2. 右上の **「共有」** をクリック
3. ダウンロードした JSON ファイル内の `client_email` の値をコピー
   - 例: `odds-viewer-readonly@your-project.iam.gserviceaccount.com`
4. 共有ダイアログにそのメールアドレスを入力
5. 権限: **「閲覧者」** を選択
6. **「送信」** をクリック

---

## 確認

- [ ] サービスアカウントが GCP Console に表示されている
- [ ] JSON キーがダウンロードされた
- [ ] スプレッドシートの共有リストに `client_email` が「閲覧者」として表示されている

---

## 次のステップ

→ [Step 24: Google OAuth クライアント作成](step24_google_oauth_client.md)
