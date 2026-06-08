# Step 38: 出馬表・距離別成績のオンデマンドスクレイピング

## 概要

AI 分析リクエスト時に、Yahoo!スポナビの出馬表ページと距離別成績ページをオンデマンドでスクレイピングし、**前走情報・馬体重・騎手名・今走距離の着別度数** を Gemini プロンプトへ追加する。

## 背景

オッズ推移だけでは「なぜオッズが動いているのか」の文脈が不足する。たとえば:
- 「支持率急増」の馬が前走ボロ負けなのかか連対なのかで文脈が全く異なる
- 馬体重が -14kg の馬への資金流入は体調不安を無視した動きかもしれない
- 今走距離（例: 芝1600m）で未勝利の馬が人気を集めているのは距離適性外への賭けである可能性がある

これらを Gemini に伝えることで、オッズ変動の「根拠あり/なし」を AI が判別しやすくなる。

**取得するページ（2ページのみ）:**
- 出馬表: `https://sports.yahoo.co.jp/keiba/race/denma/{raceId}` — 前走着順・馬体重・騎手名
- 距離別成績: `https://sports.yahoo.co.jp/keiba/race/achievement/distance/{raceId}` — 今走距離の着別度数

## 変更ファイル

| ファイル | 変更種別 |
|---|---|
| `frontend-viewer/lib/scrapeRaceData.ts` | 新規作成 |
| `frontend-viewer/app/api/odds/analysis/route.ts` | 追記（並列フェッチ＋プロンプト挿入） |
| `frontend-viewer/package.json` | `node-html-parser` を追加 |

---

## 実装詳細

### 依存パッケージの追加

```bash
cd frontend-viewer && npm install node-html-parser
```

`skills.md` の方針（クラス名への過度な依存を避け、テーブル列インデックスとテキストパターンマッチを組み合わせる）に従う。

### scrapeRaceData.ts（新規）

#### 型定義

```typescript
type HorseBasicInfo = {
  horseNumber: string;
  jockey: string;
  prevRaceName: string;    // 前走レース名（例: "大阪杯(GI)"）
  prevRaceResult: string;  // 前走着順（例: "12着"。不明は ""）
  weight: number | null;   // 馬体重（kg）
  weightDiff: number | null; // 増減（プラスが増加）
};

type HorseDistanceStat = {
  horseNumber: string;
  wins: number;   // 1着数
  second: number; // 2着数
  third: number;  // 3着数
  other: number;  // 着外数
};

type RaceSupplementalData = {
  raceDistance: string;       // 例: "芝1600m"
  basics: HorseBasicInfo[];
  distanceStats: HorseDistanceStat[];
};
```

#### extractRaceId

```typescript
/** Yahoo!スポナビのオッズURLからレースID（10桁）を抽出する */
function extractRaceId(oddsUrl: string): string | null {
  return oddsUrl.match(/\/(\d{10})(?:[/?]|$)/)?.[1] ?? null;
}
```

#### scrapeBasicData(raceId): Promise<{ raceDistance: string; basics: HorseBasicInfo[] }>

出馬表ページ（`/keiba/race/denma/{raceId}`）を fetch し、HTML を `node-html-parser` でパースして以下を馬番ごとに抽出:

- **馬番**: テーブルの馬番列
- **騎手名**: 騎手列
- **前走レース名**: 前走列のレース名テキスト
- **前走着順**: 前走列の着順数値
- **馬体重・増減**: `496(+4)` または `496(-14)` 形式の文字列をパース → `{ weight: 496, weightDiff: +4 }`
- **レース距離**: ページ上部のレース概要テキストから `芝1600m` / `ダ1200m` 等を抽出

失敗時は `console.warn` のみで `{ raceDistance: '', basics: [] }` を返す。

#### scrapeDistanceStats(raceId, raceDistance): Promise<HorseDistanceStat[]>

距離別成績ページ（`/keiba/race/achievement/distance/{raceId}`）を fetch し、`raceDistance`（例: `芝1600m`）に一致するセクションの着別度数を各馬ごとに抽出する。

着別度数の形式は `(1着-2着-3着-着外)` 例: `(3-1-2-5)` → `{ wins:3, second:1, third:3, other:5 }`

`raceDistance` が空または不明の場合は空配列を返す。失敗時は `console.warn` のみ。

#### buildSupplementalSection

`RaceSupplementalData` を受け取り、プロンプトに挿入するテキストを生成する。

```
## 出馬表情報（今走: 芝1600m）

【1番 レーベンスティール】
騎手: 戸崎圭太 / 前走: 大阪杯(GI) 12着 / 馬体重: 496kg（+4kg）/ 芝1600m実績: 0-0-0-2

【14番 ガイアフォース】
騎手: 横山武史 / 前走: 読売マイラーズC(GII) 1着 / 馬体重: 498kg（変化なし）/ 芝1600m実績: 1-3-0-2
```

- 馬体重の増減が 0 の場合は「変化なし」と表記
- 距離実績が存在しない（全0）場合は「今走距離の出走なし」と表記
- 取得できなかった馬のデータ行はスキップ
- `basics` が空の場合はセクション全体を返さない（空文字を返す）

### route.ts への追記

既存の `getOddsData()` / `getAlerts()` と **並列で** スクレイピングを実行する:

```typescript
const raceId = extractRaceId(url);
const [oddsRows, alertRows, supplementalSection] = await Promise.all([
  getOddsData(),
  getAlerts(),
  raceId ? fetchSupplemental(raceId) : Promise.resolve(''),
]);
```

`fetchSupplemental` は `scrapeBasicData` → `scrapeDistanceStats` → `buildSupplementalSection` を順次呼び出す内部関数。全体を `try-catch` で囲み、例外時は `console.warn` で空文字を返す。

**プロンプトへの挿入位置:** `## アラート履歴` セクションの直後、`## 分析指示` の直前。

```typescript
const userPrompt = `...
## アラート履歴
${alertsData}

${supplementalSection}

## 分析指示
...`;
```

`supplementalSection` が空文字の場合は改行のみになり、分析指示に影響しない。

---

## 堅牢性の担保

- 外部 HTTP リクエストは全て `try-catch` で囲む（`CLAUDE.md` の堅牢性ルールに準拠）
- Yahoo!スポナビ側のHTML構造変更によるパース失敗時はスクレイピング結果を無視し、オッズデータのみで分析を継続する
- Vercel のサーバーレス関数タイムアウト（`maxDuration = 60`）の範囲内に収まるよう、スクレイピングの並列化を徹底する

---

## 完了条件

- [ ] `node-html-parser` がインストールされていること
- [ ] AI 分析プロンプトに「## 出馬表情報」セクションが含まれていること
- [ ] スクレイピング失敗時もエラーを throw せず、出馬表セクションなしで分析が実行されること
- [ ] `npm run build`（frontend-viewer）が通ること

## ステップ完了時の更新対象

- `docs/context.md` の決定事項・議論ログ（Step 38 の設計内容を追記）
- `docs/context.md` の進捗リスト（Step 38 を ✅ に更新）
- `README.md` の「AI分析仕様 > AIに渡すデータ」テーブルに出馬表データ行を追加
- `docs/skills.md` の「フロントエンド」セクションに frontend-viewer のオンデマンドスクレイピングパターンを追記
