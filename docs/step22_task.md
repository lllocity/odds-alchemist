# タスク: Step 22 オッズ推移グラフ機能の実装

## 目的
`OddsData` シートに蓄積されたオッズデータをフロントエンドで可視化する。
レースURL・馬名を選択するだけで単勝・複勝オッズの時系列グラフが表示できる画面を既存の監視UIに追加する。

---

## 実装概要

```
[OddsData シート]
       ↓  Google Sheets API（既存インフラ流用）
[バックエンド: 新規エンドポイント3本]
       ↓  REST API
[フロントエンド: 新規コンポーネント]
       →  Recharts による折れ線グラフ表示
```

---

## サブタスク一覧

### バックエンド

#### B-1: `OddsHistoryService` の実装
- `OddsData!A:H` を全件読み込み、以下3つのユースケースを提供する
  - **URL一覧取得**: B列のユニーク値を昇順で返す
  - **馬一覧取得**: 指定URLに一致する行から D列（馬番）・E列（馬名）のユニーク組を返す（馬番昇順）
  - **オッズ時系列取得**: 指定URL・馬名に一致する行を A列（取得日時）昇順でフィルタして返す
- Sheets API 読み込み失敗時は `try-catch` で捕捉し `WARN` ログのみ、空リストを返す

#### B-2: `OddsHistoryController` の実装（3エンドポイント）

| メソッド | パス | クエリパラメータ | レスポンス例 |
|--------|------|----------------|-------------|
| GET | `/api/odds/history/urls` | なし | `["https://...", "https://..."]` |
| GET | `/api/odds/history/horses` | `url` | `[{"horseNumber":1,"horseName":"シンザン"}, ...]` |
| GET | `/api/odds/history` | `url`, `horseName` | `[{"detectedAt":"2026/03/19 10:00:00","winOdds":3.5,"placeOddsMin":1.5,"placeOddsMax":2.0}, ...]` |

#### B-3: DTO の追加
- `OddsHistoryItemDto`: `detectedAt`(String), `winOdds`(Double), `placeOddsMin`(Double), `placeOddsMax`(Double)
- `HorseDto`: `horseNumber`(Integer), `horseName`(String)

#### B-4: テストの追加
- `OddsHistoryServiceTest`: Sheets 読み込みをモック化し、フィルタ・ソートロジックを検証（最低5件）
- `OddsHistoryControllerTest`: 各エンドポイントの正常系・空データ系をカバー

---

### フロントエンド

#### F-1: Recharts の導入
- `npm install recharts` を実行
- TypeScript 型定義は recharts 本体に同梱されているため追加パッケージ不要

#### F-2: 型定義の追加
- `frontend/app/types/oddsHistory.ts` を新規作成
  - `OddsHistoryItem`: `detectedAt`, `winOdds`, `placeOddsMin`, `placeOddsMax`
  - `HorseOption`: `horseNumber`, `horseName`

#### F-3: `OddsTrendChart` コンポーネントの実装
- `frontend/app/components/OddsTrendChart.tsx` を新規作成
- **UI構成**:
  - URLドロップダウン: マウント時に `/api/odds/history/urls` を呼び出して選択肢を生成
  - 馬名ドロップダウン: URL選択後に `/api/odds/history/horses?url=xxx` を呼び出してカスケード更新
  - 「グラフ表示」ボタン: URL・馬名が両方選択された時のみ有効化
  - グラフエリア: Recharts の `LineChart` で単勝・複勝下限・複勝上限を3本の折れ線で表示
- **空データ・エラー表示**:
  - URLリストが空: 「OddsDataにデータがありません」
  - 馬名リストが空: 「このレースにデータが見つかりません」
  - オッズデータが空（シートからデータが消えた場合など）: 「該当データがありません。シートのデータが削除されている可能性があります」
- **グラフ仕様**:
  - X軸: 取得日時（`HH:mm` 形式で表示）
  - Y軸: オッズ値（自動スケール）
  - 凡例: 単勝（青）・複勝下限（緑）・複勝上限（緑破線）
  - ツールチップ: ホバー時に日時・各オッズ値を表示

#### F-4: `page.tsx` への組み込み
- 既存のアラート一覧パネルの下に `OddsTrendChart` を追加
- パネルのスタイルは既存の `bg-white rounded-xl shadow-md p-6` に統一

---

## 完了条件

- [ ] `GET /api/odds/history/urls` が OddsData シートのURL一覧を返す
- [ ] `GET /api/odds/history/horses?url=xxx` が指定URLの馬一覧を返す
- [ ] `GET /api/odds/history?url=xxx&horseName=xxx` が時系列オッズデータを返す
- [ ] フロントエンドでURLと馬名を選択してグラフが表示される
- [ ] データが存在しない場合に適切なメッセージが表示される
- [ ] バックエンドテストが全件通過する
- [ ] `docker compose up` で動作確認できる

---

## 実装時の注意事項

- Google Sheets からの読み込みは既存の `SheetsClientService`（または同等のBean）を流用する
- `OddsData` にヘッダー行が存在しない前提でパースすること（既存の Append 実装に合わせる）
- 日時文字列のパースは `DateTimeFormatter` を使い、フォーマットは `yyyy/MM/dd HH:mm:ss` を想定する
- Sheets API 読み込み時に行数が多くても全件取得で許容（データは当日分のみ・上限数千行）
