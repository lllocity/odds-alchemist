# Step 27: API Route Handlers 実装

既存 BE の OddsHistoryService と同等のロジックを、Next.js の Route Handler として実装します。
すべて Sheets 直読み（`lib/sheets.ts` 経由）で、バックエンドへの依存はありません。

---

## 実装ファイル一覧

```
frontend-viewer/app/api/
├── alerts/route.ts
└── odds/history/
    ├── route.ts
    ├── urls/route.ts
    ├── horses/route.ts
    └── alerts/route.ts
```

---

## 各 Route の仕様

### GET /api/alerts
Alerts シートの全件を逆順にして最新30件を返す。

```
入力: なし
出力: AnomalyAlert[] (最新30件)
ロジック:
  1. getAlerts() で全行取得
  2. 各行を AnomalyAlert 型にマッピング
  3. reverse() して最新順に
  4. slice(0, 30)
```

### GET /api/odds/history/urls
OddsData シートの URL 列（B列）をユニーク化して返す。

```
入力: なし
出力: string[]
ロジック:
  1. getOddsData() で全行取得
  2. B列（index 1）を抽出
  3. Set でユニーク化
```

### GET /api/odds/history/horses?url=
指定 URL のレースに登場する馬名一覧を返す。

```
入力: url (query param)
出力: string[]
ロジック:
  1. getOddsData() で全行取得
  2. B列 === url でフィルタ
  3. E列（index 4）を抽出
  4. Set でユニーク化
```

### GET /api/odds/history?url=&horseName=
指定 URL + 馬名のオッズ時系列データを返す。

```
入力: url, horseName (query params)
出力: OddsHistoryItem[]
ロジック:
  1. getOddsData() で全行取得
  2. B列 === url かつ E列 === horseName でフィルタ
  3. A列（取得日時）の昇順にソート
  4. 各行を OddsHistoryItem 型にマッピング
```

### GET /api/odds/history/alerts?url=&horseName=
指定 URL + 馬名のアラート履歴を返す（グラフのマーカー表示用）。

```
入力: url, horseName (query params)
出力: AnomalyAlert[]
ロジック:
  1. getAlerts() で全行取得
  2. B列 === url かつ E列 === horseName でフィルタ
  3. A列（検知日時）の昇順にソート
```

---

## 型定義（参照）

`types/oddsAlert.ts` の `AnomalyAlert` をそのまま使用。
`OddsHistoryItem` は OddsTrendChart.tsx が期待する型（既存の frontend/ から確認）。

---

## エラー処理

- `lib/sheets.ts` がエラー時に空配列を返すため、Route Handler 側は正常レスポンスとして `[]` を返せばよい
- URL・馬名パラメータが欠落した場合は `400 Bad Request` を返す

---

## 確認

- [ ] `GET /api/alerts` がアラートデータを返す
- [ ] `GET /api/odds/history/urls` が URL 一覧を返す
- [ ] `GET /api/odds/history/horses?url=xxx` が馬一覧を返す
- [ ] `GET /api/odds/history?url=xxx&horseName=yyy` が時系列データを返す
- [ ] `GET /api/odds/history/alerts?url=xxx&horseName=yyy` がアラート履歴を返す

---

## 次のステップ

→ [Step 28: NextAuth v5 + Google OAuth + middleware 実装](step28_nextauth.md)
