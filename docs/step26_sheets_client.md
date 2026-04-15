# Step 26: lib/sheets.ts — Sheets クライアント実装

読み取り専用サービスアカウントで Google Sheets API を呼び出す共通クライアントを実装します。

---

## 実装ファイル

`frontend-viewer/lib/sheets.ts`

---

## 仕様

### 認証
- 環境変数 `GOOGLE_SA_KEY`（サービスアカウント JSON の文字列）から認証
- `googleapis` の `google.auth.GoogleAuth` を使用

### 提供関数

| 関数 | 取得範囲 | 戻り値 |
|---|---|---|
| `getAlerts()` | `Alerts!A2:G` | `string[][]`（生データ） |
| `getOddsData()` | `OddsData!A2:H` | `string[][]`（生データ） |

### エラー処理
- 失敗時は `console.warn` でログを出力し、空配列 `[]` を返す
- システムを止めない（CLAUDE.md の堅牢性ルールに準拠）

---

## Sheets の列定義（参照）

### OddsData!A:H
| 列 | 内容 |
|---|---|
| A | 取得日時（`yyyy/MM/dd HH:mm:ss`） |
| B | URL |
| C | レース名 |
| D | 馬番 |
| E | 馬名 |
| F | 単勝オッズ |
| G | 複勝下限オッズ |
| H | 複勝上限オッズ |

### Alerts!A:G
| 列 | 内容 |
|---|---|
| A | 検知日時（`yyyy/MM/dd HH:mm:ss`） |
| B | URL |
| C | レース名 |
| D | 馬番 |
| E | 馬名 |
| F | 検知タイプ（`支持率急増` / `順位乖離` / `トレンド逸脱`） |
| G | 数値 |

---

## 確認

- [ ] ローカルで `getAlerts()` を呼び出してデータが返ってくる
- [ ] Sheets に存在しない場合は空配列が返ってくる
- [ ] SA の認証情報が不正な場合は warn ログが出て空配列が返ってくる

---

## 次のステップ

→ [Step 27: API Route Handlers 実装](step27_api_routes.md)
