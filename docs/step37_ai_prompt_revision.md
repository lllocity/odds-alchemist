# Step 37: AI分析プロンプト改訂（信頼度ウィンドウ＋新戦略）

## 概要

AI オッズ分析の判断ロジックを「三連複・ワイド前提」から「軸（中穴）＋相手（トレンド系）による単勝・馬単・三連単戦略」に刷新する。あわせて、発走時刻に近いデータを判断の主材料とする「信頼度ウィンドウ（層A/B）」をプロンプトに組み込む。

## 背景

`docs/202606_verdict_logic_gap.html` で特定された3つのギャップ（verdict ラベル体系・アラート役割分担・券種）と、`docs/202606_implementation_plan.html` で設計した「信頼度ウィンドウの組み込み」を1ステップで対応する。

現状のプロンプトは三連複・ワイド前提の「本命/対抗/3着紐/消し」体系で書かれており、今回設計した「中穴軸＋トレンド系相手」の戦略とは前提が異なる。両方の不整合を解消しないと新戦略は買い目に反映されない。

## 変更ファイル

| ファイル | 変更種別 |
|---|---|
| `frontend-viewer/app/api/odds/analysis/route.ts` | 主要変更（buildHorsesData・プロンプト・JSONスキーマ） |
| `frontend-viewer/app/components/OddsAnalysis.tsx` | 型定義・スタイル・buildBets・UI |

---

## route.ts の実装詳細

### 1. buildHorsesData に層タグ付けを追加

各馬の時系列行の末尾に `[参考]`（層A）または `[直前]`（層B）タグを埋め込む。

**層判定ロジック（時刻差ヒューリスティック）:**

`HorseRow` に `detectedAtMinutes: number | null` フィールドを追加し、`detectedAt`（"yyyy/MM/dd HH:mm:ss"）の時刻部分から `HH*60+MM` の分値を保持する。行を出力する際に前レコードとの差分で層を判定する。

```
差 ≥ 20分 → [参考]  （30分ポーリング区間 = 発走1時間以上前）
差 < 20分 → [直前]  （5分/1分ポーリング区間 = 発走1時間以内）
最初のレコード（比較対象なし） → [参考]
```

**出力イメージ:**
```
14:10, 8.2, 3.1, 4.0, 5.0%, -0.2, -0.1, [参考]
14:40, 7.8, 2.9, 3.8, 9.7%, -0.4, -0.2, [参考]
15:15, 7.1, 2.6, 3.5, 13.4%, -0.3, -0.2, [直前]
15:20, 6.5, 2.4, 3.2, 20.7%, -0.6, -0.3, [直前] *支持率急増
```

既存の `alertSuffix`（`*アラート種別`）とは別に、行末に層タグを付与する。順序は `alertSuffix` → 層タグ の順。

### 2. システムプロンプトの verdict 制約を変更

```
【絶対ルール】verdict の頭数制約（いかなる場合も必ず守ること）:
- 軸候補は最大2頭まで
- 相手候補は最大4頭まで
- 上限に達したら追加しないこと。上限まで無理に埋める必要はない
```

### 3. ユーザープロンプトの全面改訂

#### 「データの見方」セクションに追記（既存項目の末尾に追加）

```
- [直前] タグ: 発走1時間以内のデータ（判断の主材料）
- [参考] タグ: 発走1時間以上前のデータ（トレンド変化量計算の基準点）
  → [直前] タグ付きデータの挙動を重視して分析すること
```

#### 「verdict 判断の基本原則」セクションを全面置換

```
verdict の定義:
- 軸候補: 中穴ゾーンの馬（ロジックFの断層直上付近）。単勝・馬単・三連単の軸
- 相手候補: オッズ推移のトレンドが上方修正されている馬。馬単・三連単で軸に絡める相手
- 対象外: 根拠が薄い・推移が不安定・中穴ゾーン外かつトレンドシグナルなし

verdict の選択プロセス（この順番で決定すること）:
【Step 1】ロジックFの断層アラートを参照し、断層の境界付近（高人気でも大穴でもない中穴帯）の馬を特定する
【Step 2】軸候補を選ぶ（最大2頭）: 中穴ゾーン内で断層に最も近い1〜2頭を選ぶ
【Step 3】相手候補を選ぶ（最大4頭）: C・D シグナルが出ている馬を主軸に、A・E シグナルで補強
【Step 4】残りはすべて「対象外」にする
```

「掛け合わせの目安」（オッズ水準×トレンド強度のテーブル）は削除する（新戦略では断層位置とトレンドシグナルで判断するため不要）。

#### 「アラートの種類」セクションを3グループに再構成

```
## アラートの種類と役割

### グループ1 — 相手候補選定の主要シグナル（個別馬の verdict に直接影響）
- 支持率加速（ロジックD）: 支持率の変化速度が 0.5%/分 以上 → 最強シグナル。相手候補に最優先で引き上げる
- トレンド逸脱（ロジックC）: 当日初回比で支持率 +5% 以上（5〜12番人気） → 相手候補の主要選定基準

### グループ2 — 相手候補選定の補強シグナル（同時発生で確度を高める）
- 支持率急増（ロジックA）: 短時間で支持率 +2% 以上（4番人気以下） → 単発でも相手候補候補。C・D と同時なら確信度大
- フェーズ逸脱[10分前]（ロジックE）: 最重要フェーズ。相手候補の評価を1段上げる根拠
- フェーズ逸脱[30分前]（ロジックE）: 継続的な資金流入の証拠。相手候補の確度を高める
- フェーズ逸脱[朝]（ロジックE）: 長時間継続の支持。補強材料として評価

### グループ3 — 荒れレース判定専用（個別馬の verdict には影響させないこと）
- 順位乖離 / 順位乖離[拡大中] / 順位乖離[解消中]（ロジックB）: レース全体の混戦度合いの判定に使用。`is_chaotic_race` の根拠とする
- オッズ断層[凝縮] / オッズ断層[拡散]（ロジックF）: 断層位置で中穴ゾーンを定義（Step 1 で使用）。断層の凝縮/拡散は荒れ判定にも使用
```

**verdict 判定への影響指針（旧テーブル）は削除**。代わりに上記3グループの説明のみ残す。

#### comment の指示文を変更

```
"comment": "軸候補の場合は中穴ゾーンである根拠（断層位置・人気順位）を、相手候補の場合は採用したシグナル（C/D/A/E）を、対象外の場合は外してよい理由を2〜4文で。単勝・馬単・三連単での役割に言及すること"
```

#### summary の指示文を変更

```
"summary": "全体所感と今回の軸（中穴）× 相手（トレンド系）による単勝・馬単・三連単の戦略を2〜3文で"
```

### 4. JSON スキーマに 2 フィールドを追加

```json
{
  "horses": [...],
  "trend_summary": "...",
  "summary": "...",
  "confidence_score": 80,
  "is_chaotic_race": false,
  "chaotic_note": "B・Fアラートの発生状況と荒れ判定の根拠。B/Fシグナルなしの場合は「なし」と記述すること"
}
```

---

## OddsAnalysis.tsx の実装詳細

### 1. 型定義の変更

```typescript
type HorseAnalysis = {
  number: number;
  name: string;
  verdict: '軸候補' | '相手候補' | '対象外';
  comment: string;
  trend_evidence: string;
};

type AnalysisResult = {
  horses: HorseAnalysis[];
  trend_summary: string;
  summary: string;
  confidence_score: number;
  model: string;
  is_chaotic_race: boolean;
  chaotic_note: string;
};
```

### 2. VERDICT_STYLES の更新

```typescript
const VERDICT_STYLES: Record<string, string> = {
  '軸候補':   'bg-emerald-100 text-emerald-800 border border-emerald-200',
  '相手候補': 'bg-blue-100 text-blue-800 border border-blue-200',
  '対象外':   'bg-gray-100 text-gray-400 border border-gray-200',
};
```

### 3. buildBets の刷新（三連複・ワイド → 単勝・馬単・三連単）

```typescript
function buildBets(horses: HorseAnalysis[], isChaoticRace: boolean) {
  const jiku    = horses.filter(h => h.verdict === '軸候補');
  const aite    = horses.filter(h => h.verdict === '相手候補');
  if (jiku.length === 0) return null;

  // 単勝: 軸候補の馬全員
  const tansho = jiku;

  // 馬単（逆流し）: 軸候補1着 × 相手候補2着 の全組み合わせ
  type UmatanBet = { first: HorseAnalysis; second: HorseAnalysis };
  const umatan: UmatanBet[] = [];
  for (const j of jiku) {
    for (const a of aite) {
      umatan.push({ first: j, second: a });
    }
  }

  // 三連単: is_chaotic_race のときのみ
  // 軸候補1着 → 相手候補2着・3着 の流し（2着・3着に相手候補を総流し）
  type SanrentanBet = { first: HorseAnalysis; second: HorseAnalysis; third: HorseAnalysis };
  const sanrentan: SanrentanBet[] = [];
  if (isChaoticRace && aite.length >= 2) {
    for (const j of jiku) {
      for (let i = 0; i < aite.length; i++) {
        for (let k = i + 1; k < aite.length; k++) {
          sanrentan.push({ first: j, second: aite[i], third: aite[k] });
        }
      }
    }
  }

  const total = tansho.length + umatan.length + sanrentan.length;
  return { tansho, umatan, sanrentan, total };
}
```

### 4. 荒れ判定バナーの追加（UI）

`is_chaotic_race === true` のとき、推移分析サマリーの上部に黄色の警告バナーを表示する:

```tsx
{result.is_chaotic_race && (
  <div className="bg-amber-50 border border-amber-200 rounded-lg p-3">
    <p className="text-xs font-bold text-amber-800">⚠ 荒れレースシグナルあり — 三連単サブ買いを検討</p>
    <p className="text-xs text-amber-700 mt-1">{result.chaotic_note}</p>
  </div>
)}
```

---

## 完了条件

- [ ] `[参考]`/`[直前]` タグが各馬の時系列データに正しく付与される
- [ ] Gemini が返す verdict が `軸候補`/`相手候補`/`対象外` のいずれかになっている
- [ ] `is_chaotic_race` と `chaotic_note` フィールドが JSON に含まれている
- [ ] 買い目推奨が「単勝・馬単（・三連単）」で表示される
- [ ] `npm run build`（frontend-viewer）が通る

## ステップ完了時の更新対象

- `docs/context.md` の決定事項・議論ログ（Step 37 の設計内容を追記）
- `docs/context.md` の進捗リスト（Step 37 を ✅ に更新）
- `README.md` の「AI分析仕様」セクション（新 verdict 体系・層A/B・券種を反映）
