# Step 29: 閲覧用 page.tsx・コンポーネント移植

既存の管理用 FE からコンポーネントをコピーし、閲覧用ページを組み立てます。

---

## コピーするファイル

| コピー元 | コピー先 | 変更 |
|---|---|---|
| `frontend/app/components/AlertList.tsx` | `frontend-viewer/app/components/AlertList.tsx` | なし |
| `frontend/app/components/OddsTrendChart.tsx` | `frontend-viewer/app/components/OddsTrendChart.tsx` | API パス変更（後述） |
| `frontend/app/types/oddsAlert.ts` | `frontend-viewer/app/types/oddsAlert.ts` | なし |
| `frontend/public/logo.png` | `frontend-viewer/public/logo.png` | なし |

---

## OddsTrendChart.tsx の変更点

現在の実装は `process.env.NEXT_PUBLIC_API_BASE_URL` をプレフィックスとして API を呼び出している。
閲覧用 FE では同一オリジンの Route Handler に相対パスで呼び出すため、プレフィックスを削除する。

```ts
// 変更前（OddsTrendChart.tsx 内）
const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL || '';
fetch(`${apiBaseUrl}/api/odds/history/urls`)

// 変更後
fetch('/api/odds/history/urls')
```

※ `NEXT_PUBLIC_API_BASE_URL` への参照をすべて削除し、パスを直書きに変更する

---

## page.tsx の実装

### レイアウト（既存の管理用 FE を踏襲）

```
[ヘッダーバー: ロゴ + タイトル]

[メインエリア: 2カラム]
左カラム:
  - オッズ推移グラフパネル

右カラム（sticky）:
  - 買いの掟パネル
  - 検知アラートパネル
```

### fetchAlerts の変更

```ts
// 変更前（管理用 FE）
fetch(`${apiBaseUrl}/api/odds/alerts`)
// 変更後（閲覧用 FE）
fetch('/api/alerts')
```

### ポーリング対象

`fetchAlerts` のみ（監視 URL の取得は閲覧用 FE では不要）。

---

## 開発サーバー起動

```bash
cd frontend-viewer
npm run dev -- --port 3001
```

`http://localhost:3001` でアクセス（ログイン後にコンテンツが表示される）

---

## 確認

- [ ] Google ログイン後にページが表示される
- [ ] オッズ推移グラフで URL・馬名を選択するとグラフが描画される
- [ ] 検知アラートが最新30件表示される（10秒ごとに自動更新）
- [ ] 「買いの掟」が表示される

---

## 次のステップ

→ [Step 30: 管理用 frontend/ から閲覧系機能を削除](step30_admin_fe_cleanup.md)
