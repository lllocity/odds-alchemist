# Step 39: 着順記録機能（戦略検証データの保全）

## 概要

レース終了後に着順（1〜3着の馬番）を記録する機能を閲覧用 FE に追加する。Google Sheets の `Results` シートに保存し、将来の戦略検証（「軸候補/相手候補の選定は機能していたか」）に使用する。

## 背景

`docs/202606_strategy_summary.html` で定めたデータ保持ポリシー:

> 検証に最低限必要な3項目: ① 最終オッズ　② 主要アラートの発生有無　③ 着順（結果）

①②は既に Google Sheets に記録されている。③の着順のみが未記録のため、戦略の事後検証が現時点では行えない。着順を記録することで、数カ月後に「中穴軸は実際に来ていたか」「トレンド系相手候補は3着以内に入っていたか」を定量的に評価できる。

## 変更ファイル

| ファイル | 変更種別 |
|---|---|
| `frontend-viewer/app/api/results/route.ts` | 新規（POST エンドポイント） |
| `frontend-viewer/lib/sheets.ts` | 追記（Results シートへの書き込み関数） |
| `frontend-viewer/app/components/ResultRecorder.tsx` | 新規（着順入力UI） |
| `frontend-viewer/app/page.tsx` | 追記（ResultRecorder を配置） |

---

## Google Sheets の変更

### Results シート（新規）

| 列 | 内容 | 形式 |
|---|---|---|
| A | 記録日時 | yyyy/MM/dd HH:mm:ss |
| B | URL（レース識別） | https://... |
| C | レース名 | テキスト |
| D | 1着馬番 | 数値 |
| E | 2着馬番 | 数値 |
| F | 3着馬番 | 数値 |
| G | メモ | テキスト（任意） |

書き込みは `appendData("Results!A:G", rows)` で追記のみ（OddsData・Alerts と同方式）。

---

## 実装詳細

### API Route: POST /api/results

```typescript
// リクエストボディ
type RecordResultRequest = {
  url: string;
  raceName: string;
  first: number;   // 1着馬番
  second: number;  // 2着馬番
  third: number;   // 3着馬番
  memo?: string;
};
```

バリデーション: `first`/`second`/`third` が 1〜18 の整数であること。失敗時は 400 を返す。

Google Sheets への書き込みは `try-catch` で保護。失敗時は `console.warn` + 500 を返す。

### ResultRecorder コンポーネント

閲覧用 FE の OddsAnalysis パネル（または独立パネル）の下部に配置する。

UI 要素:
- URL ドロップダウン（OddsTrendChart の URL 選択と同じ一覧を再利用）
- 1着・2着・3着それぞれの馬番入力（数値入力フィールド、1〜18）
- メモ入力（任意、1行テキスト）
- 「記録する」ボタン

送信後は「記録しました」のインライン表示を3秒間表示してフォームをリセット。

---

## 堅牢性・権限

- 閲覧用 FE は NextAuth で認証済みユーザーのみアクセス可能なため、外部からの誤記録リスクは低い
- `appendData` は追記のみのため既存データを壊さない
- Results シートに書き込む際の Google サービスアカウントは**既存の読み書き SA**（backend が使うものと同一）を使用する予定。閲覧用 FE が現在使っている読み取り専用 SA では書き込み不可のため、環境変数の設定変更が必要

### 権限の選択肢

| 選択肢 | メリット | デメリット |
|---|---|---|
| A. 読み書き SA のキーを frontend-viewer 環境変数に追加 | シンプル | 閲覧用 FE に書き込み権限が増える |
| B. Results シートへの書き込み専用 SA を新規作成 | 最小権限原則 | SA を1つ追加する手間 |

**推奨: 選択肢A**。Results シートは個人用途かつ全レコードが append-only なので、書き込み権限が増えても被害は限定的。SA の管理コストが低い方を優先する。

---

## 完了条件

- [ ] POST /api/results が正常に動作し、Google Sheets `Results!A:G` に着順データが記録される
- [ ] ResultRecorder コンポーネントが閲覧用 FE に表示される
- [ ] バリデーションエラー（馬番が範囲外など）が適切に UI に表示される
- [ ] `npm run build`（frontend-viewer）が通ること

## ステップ完了時の更新対象

- `docs/context.md` の決定事項・議論ログ（Step 39 の設計内容を追記）
- `docs/context.md` の進捗リスト（Step 39 を ✅ に更新）
- `README.md` の「Google Sheets の構成」テーブルに `Results` シートを追加
- `docs/skills.md` の Google Sheets APIルールに `Results` シートの列定義を追記
- メモリファイル（`/memory/`）の Google Sheets 列定義に `Results!A:G` を追記
