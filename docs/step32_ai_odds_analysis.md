# Step 32: AIオッズ分析機能

## 概要
オッズ推移データを生成AI（Gemini）に渡し、「どの馬が本命・対抗・紐として有望か」を自然言語で回答させる。閲覧用FEにオンデマンドで表示する。

## 前提・方針
- **AI**: Google Gemini API（`gemini-2.5-pro`）— 無料枠（25リクエスト/日）を使用。オンデマンド個人利用では十分な量
- **実行タイミング**: オンデマンド（ユーザーがボタンを押したときのみ）
- **データ**: 既存の OddsData / Alerts シートのみ（外部データなし）
- **実装先**: 閲覧用FE（Next.js API route）のみ。Java バックエンドは変更しない

---

## タスク一覧

### Step 32-1: 事前準備（手動作業）
- [ ] [Google AI Studio](https://aistudio.google.com/apikey) で API キーを発行
- [ ] `frontend-viewer/.env.local` に追記:
  ```
  GEMINI_API_KEY=<発行したキー>
  ```
- [ ] Vercel のプロジェクト設定 → 環境変数に `GEMINI_API_KEY` を追加（本番用）

### Step 32-2: SDK インストール
```bash
cd frontend-viewer
npm install @google/generative-ai
```

### Step 32-3: API route 作成
**ファイル**: `frontend-viewer/app/api/odds/analysis/route.ts`（新規）

- `GET /api/odds/analysis?url={raceUrl}`
- 既存の `getOddsData()` / `getAlerts()` を流用してシートからデータ取得
- 指定 URL の全馬データをプロンプト用テキストに整形
- `gemini-2.5-pro` を呼び出し（`responseMimeType: "application/json"` で JSON を直接返させる）
- `export const maxDuration = 30;` を設定（Vercel serverless タイムアウト対策）
- JSON レスポンスをパースして `NextResponse.json()` で返す

**レスポンス形式（JSON）**:
```json
{
  "honmei": { "number": 1, "name": "馬名", "reason": "理由" },
  "taikou": { "number": 2, "name": "馬名", "reason": "理由" },
  "himo": [
    { "number": 3, "name": "馬名", "reason": "理由" }
  ],
  "summary": "全体所感テキスト",
  "confidence_score": 75
}
```
※ `number` は馬番。`confidence_score` は 1〜100 で AI 自身の推定確度。

### Step 32-3-1: プロンプト仕様（参照資料）
> ここは実装ステップではなく、Step 32-3 で route.ts を実装する際のプロンプト内容の仕様書です。

**システムプロンプト:**
```
あなたは日本の競馬のオッズ動向を分析する専門家です。
提供されたオッズ推移データとアラート情報をもとに、買い目を推奨してください。
回答は指示されたJSON形式のみを出力してください。前置き・後書き・解説文は一切不要です。
```

**ユーザープロンプト（データ埋め込み後）:**
```
以下は【{raceName}】のオッズ推移データです。

## データの見方
- 単勝オッズ: 1着的中時の払戻倍率。数値が低いほど支持率が高い（人気馬）
- 複勝オッズ（下限〜上限）: 3着以内的中時の払戻倍率の範囲
- 変化率: 最初の取得値を基準とした単勝オッズの変化率（マイナス＝下落＝支持増）
- 「*」マーク: その時点でアラートが発生していることを示す

## アラートの種類
- 支持率急増: 短時間で単勝オッズが大幅に下落した（急激な人気集中）
- 順位乖離: 単勝人気順位と複勝人気順位が大きくずれている（穴人気の可能性）
- トレンド逸脱: オッズ推移が通常のパターンから外れた動きをしている

## 各馬のオッズ推移

{horsesData}

## アラート履歴

{alertsData}
※ アラートが無い場合は「なし」

## 分析指示
上記データから「異常な支持の集中」と「支持の安定性」を多角的に分析し、以下のJSON形式で出力してください。

【判断のロジック】
1. 軸馬の選定: 複勝オッズ下限が安定して低く、かつ単勝オッズが緩やかに下落している馬を優先
2. 穴馬の選定: 単勝人気に対し複勝人気が著しく高い馬（順位乖離）、または直前で支持率が急増した馬
3. 紐の点数管理: 期待値が高いと判断した順に最大3頭まで。広げすぎない

必ず以下のJSON構造のみを出力し、余計な解説文は一切含めないでください。

{
  "honmei": { "number": 馬番(数値), "name": "馬名", "reason": "根拠" },
  "taikou": { "number": 馬番(数値), "name": "馬名", "reason": "根拠" },
  "himo": [
    { "number": 馬番(数値), "name": "馬名", "reason": "根拠" }
  ],
  "summary": "レース全体の傾向・注目ポイントを2〜3文で",
  "confidence_score": データの充実度と動向の明確さから判断した推奨確度(1-100の整数)
}

根拠が薄い・データ不足の場合は reason と summary にその旨を明記してください。
```

**プロンプトへのデータ整形ルール（`{horsesData}` の生成）:**

CSV形式でトークンを節約しつつ、事前計算済みの変化率とアラートフラグを付与する。
```
【{horseNumber}番 {horseName}】
時刻,単勝,複勝下限,複勝上限,変化率
{HH:mm1},{winOdds1},{placeMin1},{placeMax1},-
{HH:mm2},{winOdds2},{placeMin2},{placeMax2},{変化率%}
{HH:mm3},{winOdds3},{placeMin3},{placeMax3},{変化率%} *{アラート種別}
...（直近20件まで。それ以上は省略）
```
- 時刻は `HH:mm` 形式に短縮（日付は省略）
- 変化率 = `(現在単勝 - 初回単勝) / 初回単勝 × 100`（小数第1位、マイナスが下落）
- アラート発生行末尾に ` *支持率急増` のように付記

**プロンプトへのデータ整形ルール（`{alertsData}` の生成）:**
```
- {HH:mm} {horseNumber}番 {horseName}：{alertType}（値:{value}）
```

### Step 32-4: フロントエンドコンポーネント作成
**ファイル**: `frontend-viewer/app/components/OddsAnalysis.tsx`（新規）

- Props: `url: string`（選択中のレースURL）
- 「AI分析を実行」ボタン
- 状態管理: ローディング / エラー / 結果
- 結果カード表示:
  - 🥇 本命 / 🥈 対抗 / 🎯 紐候補（馬名 + 理由）
  - 全体所感
  - `confidence_score` を「推奨確度: XX/100」として表示

### Step 32-5: ページへの組み込み
**ファイル**: `frontend-viewer/app/page.tsx`

- オッズ推移グラフパネルの下に `<OddsAnalysis url={selectedUrl} />` を追加
- `selectedUrl` が空の場合は非表示

### Step 32-6: 動作確認
- [ ] ローカル（`npm run dev`）でレース選択 → AI分析ボタン動作確認
- [ ] 本命・対抗・紐候補と理由が表示されること
- [ ] エラー時（無効なキー等）にエラーメッセージが表示されること
- [ ] Vercel へ deploy して本番動作確認

---

## 注意事項
- Gemini 2.5 Pro の無料枠上限（25リクエスト/日）を超えると課金が発生するため、ボタン連打防止のため分析中は再度押せないようにする（`isLoading` フラグ）
- `GEMINI_API_KEY` は `.env.local` で管理（`.gitignore` 済み）。リポジトリにコミットしないこと
- プロンプトに含めるデータが多い場合は直近20件に絞る（コンテキスト節約。整形ルール参照）
- **Vercel タイムアウトの確認が必須**: Gemini 2.5 Pro の Thinking モードは応答が遅く、18頭分のデータ処理で30秒を超える可能性がある。Vercel Hobby プランの serverless function 上限は **10秒** のため、タイムアウトエラーになる可能性が高い。対処方法は以下のいずれか:
  - Vercel Pro プランにアップグレード（`maxDuration` を最大300秒まで設定可能）
  - モデルを `gemini-2.5-flash` に変更して応答速度を優先する
- **Thinking トークンについて**: Gemini 2.5 Pro は内部思考（Thinking）が有効なため、複雑な推論は自動で行われる。`responseMimeType: "application/json"` を指定すれば JSON のみが最終出力として返され、思考過程はレスポンスに含まれない
- **プロンプト内 `{}` と文字列テンプレートの衝突に注意**: ユーザープロンプトにはJSONスキーマ例として `{` `}` が含まれる。実装時に TypeScript のテンプレートリテラル（`` ` `` ）で変数を埋め込む場合、`${horsesData}` 形式を使い、スキーマ部分の `{` `}` はそのまま文字列として扱う（二重展開にならないよう注意）
- **変化率計算の null ガード**: `winOdds` が `null` の行がある場合は変化率を `-` として扱い、`NaN` が出力されないようにする
- **無料枠（25 req/日）を使い切った場合**: 翌日まで待つか、Google Cloud の従量課金に切り替える。費用は 1回あたり数円程度の見込み
- **`getAlerts()` の関数名は実装前に `frontend-viewer/lib/sheets.ts` で確認すること**
