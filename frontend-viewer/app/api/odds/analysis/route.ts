import { NextRequest, NextResponse } from 'next/server';
import { GoogleGenerativeAI } from '@google/generative-ai';
import { getOddsData, getAlerts } from '@/lib/sheets';

export const maxDuration = 60;

function toFloat(s: string | undefined): number | null {
  if (!s) return null;
  const n = parseFloat(s);
  return isNaN(n) ? null : n;
}

/** "yyyy/MM/dd HH:mm:ss" 形式の detectedAt から分単位の整数を返す（層タグ判定用） */
function parseMinutes(detectedAt: string | undefined): number | null {
  const timePart = detectedAt?.split(' ')[1];
  if (!timePart) return null;
  const [h, m] = timePart.split(':').map(Number);
  return (isNaN(h) || isNaN(m)) ? null : h * 60 + m;
}

/** 直近5件の前回比から推移トレンドを分類する */
function classifyTrend(velocities: (number | null)[]): string {
  const valid = velocities.filter((v): v is number => v !== null);
  if (valid.length === 0) return '横ばい';
  const last5 = valid.slice(-5);
  const sum5 = last5.reduce((a, b) => a + b, 0);
  const negCount = last5.filter(v => v < 0).length;
  const minV = Math.min(...last5);

  if (minV <= -2.0) return '急落';
  if (sum5 <= -0.5 && negCount >= 3) return '継続下落中';
  if (sum5 >= 0.3) {
    const allSum = valid.reduce((a, b) => a + b, 0);
    return allSum < -0.5 ? '反発中' : '上昇中';
  }
  if (valid.length > 5) {
    const earlier = valid.slice(0, -5);
    const earlierSum = earlier.reduce((a, b) => a + b, 0);
    if (earlierSum < -0.5 && Math.abs(sum5) < 0.3) return '下落後安定';
  }
  return '横ばい';
}

function buildHorsesData(oddsRows: string[][], url: string, alertRows: string[][]): string {
  type HorseRow = {
    time: string;
    winOdds: number | null;
    placeMin: number | null;
    placeMax: number | null;
    detectedAtMinutes: number | null;
  };
  const horseMap = new Map<string, { number: string; name: string; rows: HorseRow[] }>();

  for (const row of oddsRows) {
    const [detectedAt, rowUrl, , horseNum, horseName, win, placeMin, placeMax] = row;
    if (rowUrl !== url) continue;
    const key = `${horseNum}:${horseName}`;
    if (!horseMap.has(key)) {
      horseMap.set(key, { number: horseNum ?? '', name: horseName ?? '', rows: [] });
    }
    const timePart = detectedAt?.split(' ')[1]?.slice(0, 5) ?? detectedAt ?? '';
    horseMap.get(key)!.rows.push({
      time: timePart,
      winOdds: toFloat(win),
      placeMin: toFloat(placeMin),
      placeMax: toFloat(placeMax),
      detectedAtMinutes: parseMinutes(detectedAt),
    });
  }

  type AlertEntry = { time: string; type: string };
  const alertMap = new Map<string, AlertEntry[]>();
  for (const row of alertRows) {
    const [detectedAt, rowUrl, , horseNum, horseName, alertType] = row;
    if (rowUrl !== url) continue;
    const key = `${horseNum}:${horseName}`;
    if (!alertMap.has(key)) alertMap.set(key, []);
    const timePart = detectedAt?.split(' ')[1]?.slice(0, 5) ?? detectedAt ?? '';
    alertMap.get(key)!.push({ time: timePart, type: alertType ?? '' });
  }

  const sorted = [...horseMap.entries()].sort(
    (a, b) => parseInt(a[1].number) - parseInt(b[1].number)
  );

  const parts: string[] = [];

  for (const [key, horse] of sorted) {
    const dataRows = horse.rows.slice(-20);
    const firstWin = dataRows.find(r => r.winOdds !== null)?.winOdds ?? null;
    const alerts = alertMap.get(key) ?? [];

    const velocities: (number | null)[] = [];
    let prevWin: number | null = null;
    let prevMinutes: number | null = null;

    const lines: string[] = [
      `【${horse.number}番 ${horse.name}】`,
      '時刻,単勝,複勝下限,複勝上限,単勝変化率,単勝前回比,複勝前回比,層',
    ];

    const placeVelocities: (number | null)[] = [];
    let prevPlaceMin: number | null = null;

    for (const r of dataRows) {
      const cumRate =
        firstWin !== null && r.winOdds !== null
          ? `${(((r.winOdds - firstWin) / firstWin) * 100).toFixed(1)}%`
          : '-';
      const velocity = r.winOdds !== null && prevWin !== null ? r.winOdds - prevWin : null;
      velocities.push(velocity);
      const placeVelocity = r.placeMin !== null && prevPlaceMin !== null ? r.placeMin - prevPlaceMin : null;
      placeVelocities.push(placeVelocity);

      const velStr = velocity !== null ? velocity.toFixed(1) : '-';
      const placeVelStr = placeVelocity !== null ? placeVelocity.toFixed(1) : '-';
      const alertMatch = alerts.find(a => a.time === r.time);
      const alertSuffix = alertMatch ? ` *${alertMatch.type}` : '';

      // 前レコードとの時刻差で層A/B を判定（≥20分→[参考]、<20分→[直前]）
      const diff = (r.detectedAtMinutes !== null && prevMinutes !== null)
        ? r.detectedAtMinutes - prevMinutes
        : null;
      const layerTag = (diff === null || diff >= 20) ? '[参考]' : '[直前]';

      lines.push(
        `${r.time},${r.winOdds?.toFixed(1) ?? '-'},${r.placeMin?.toFixed(1) ?? '-'},${r.placeMax?.toFixed(1) ?? '-'},${cumRate},${velStr},${placeVelStr}${alertSuffix} ${layerTag}`
      );
      if (r.detectedAtMinutes !== null) prevMinutes = r.detectedAtMinutes;
      if (r.winOdds !== null) prevWin = r.winOdds;
      if (r.placeMin !== null) prevPlaceMin = r.placeMin;
    }

    const trend = classifyTrend(velocities);
    const last5valid = velocities.filter((v): v is number => v !== null).slice(-5);
    const last5sum =
      last5valid.length > 0 ? last5valid.reduce((a, b) => a + b, 0).toFixed(1) : '-';
    const last5placeValid = placeVelocities.filter((v): v is number => v !== null).slice(-5);
    const last5placeSum =
      last5placeValid.length > 0 ? last5placeValid.reduce((a, b) => a + b, 0).toFixed(1) : '-';
    lines.push(`▶ 単勝トレンド: ${trend} | 単勝最終5件変化: ${last5sum} | 複勝前回比合計: ${last5placeSum}`);

    parts.push(lines.join('\n'));
  }

  return parts.join('\n\n');
}

function buildAlertsData(alertRows: string[][], url: string): string {
  const relevant = alertRows.filter(row => row[1] === url);
  if (relevant.length === 0) return 'なし';
  return relevant
    .map(row => {
      const [detectedAt, , , horseNum, horseName, alertType, value] = row;
      const timePart = detectedAt?.split(' ')[1]?.slice(0, 5) ?? detectedAt ?? '';
      return `- ${timePart} ${horseNum}番 ${horseName}：${alertType}（値:${value}）`;
    })
    .join('\n');
}

const ALLOWED_MODELS = ['gemini-3-flash-preview', 'gemini-3.1-flash-lite', 'gemini-2.5-flash'] as const;
const DEFAULT_MODEL = 'gemini-3-flash-preview';

export async function GET(req: NextRequest) {
  const url = req.nextUrl.searchParams.get('url');
  if (!url) {
    return NextResponse.json({ error: 'url パラメータが必要です' }, { status: 400 });
  }

  try {
    const [oddsRows, alertRows] = await Promise.all([getOddsData(), getAlerts()]);

    const urlRows = oddsRows.filter(row => row[1] === url);
    if (urlRows.length === 0) {
      return NextResponse.json({ error: '指定されたURLのデータが見つかりません' }, { status: 404 });
    }

    const raceName = urlRows[0][2] ?? url;
    const horsesData = buildHorsesData(oddsRows, url, alertRows);
    const alertsData = buildAlertsData(alertRows, url);

    const systemPrompt = `あなたは日本の競馬のオッズ動向を分析する専門家です。
提供されるデータは一般公開されていない「オッズ推移（時系列）」であり、最新オッズだけでは見えない市場の意図や資金の流れが記録されています。
「現時点のオッズ水準」ではなく「どのように変化してきたか（軌跡）」を重視して分析してください。
回答は指示されたJSON形式のみを出力してください。前置き・後書き・解説文は一切不要です。

【絶対ルール】verdict の頭数制約（いかなる場合も必ず守ること）:
- 軸候補は最大2頭まで
- 相手候補は最大4頭まで
- 上限に達したら追加しないこと。上限まで無理に埋める必要はない`;

    const userPrompt = `以下は【${raceName}】のオッズ推移データです。

## データの見方
- 単勝オッズ: 1着的中時の払戻倍率。数値が低いほど支持率が高い（人気馬）
- 複勝オッズ（下限〜上限）: 3着以内的中時の払戻倍率の範囲
- 単勝変化率: 最初の取得値を基準とした単勝オッズの変化率（マイナス＝下落＝支持増）
- 単勝前回比: 前回取得値との単勝オッズの差（マイナス＝下落＝支持増）
- 複勝前回比: 前回取得値との複勝下限の差（マイナス＝下落＝3着以内の支持増）
- 「*」マーク: その時点でアラートが発生していることを示す
- ▶ トレンド行: 直近5件の動向分類と合計変化量
- [直前] タグ: 発走1時間以内のデータ（判断の主材料）
- [参考] タグ: 発走1時間以上前のデータ（トレンド変化量計算の基準点）
  → [直前] タグ付きデータの挙動を重視して分析すること

## verdict の定義と選択プロセス

verdict の定義:
- 軸候補: 中穴ゾーンの馬（オッズ断層の直上付近）。単勝・馬単・三連単の軸として狙う
- 相手候補: オッズ推移のトレンドが上方修正されている馬。馬単・三連単で軸に絡める相手
- 対象外: 根拠が薄い・推移が不安定・中穴ゾーン外かつトレンドシグナルなし

verdict の選択プロセス（この順番で決定すること）:
【Step 1】アラート履歴に「オッズ断層」シグナル（ロジックF）があれば、断層の境界付近（高人気でも大穴でもない中穴帯）の馬を特定する。断層シグナルがない場合は、全馬のオッズ分布から相対的な中穴帯を判断する
【Step 2】軸候補を選ぶ（最大2頭）: 中穴ゾーン内で断層に最も近い1〜2頭を選ぶ。根拠が薄ければ1頭でよい
【Step 3】相手候補を選ぶ（最大4頭）: C・D シグナルが出ている馬を主軸に、A・E シグナルで補強して選ぶ。シグナルがない場合はトレンド形状と [直前] タグのデータから総合判断する
【Step 4】残りはすべて「対象外」にする
※ 各 Step で上限に達したら追加しないこと。上限まで無理に埋める必要はない

複勝推移との組み合わせ:
- 単勝下落 + 複勝も下落 → 市場全体が支持。信頼度UP
- 単勝横ばい/高い + 複勝下落 → 「飛ぶよりは来る」市場評価。相手候補として評価
- 単勝上昇 + 複勝上昇 → 複合的な人気離れシグナル。対象外の根拠
- 単勝高い + 複勝のみ下落 → 3着争いに絡む可能性。相手候補として評価

## アラートの種類と役割

### グループ1 — 相手候補選定の主要シグナル（個別馬の verdict に直接影響）
- 支持率加速（ロジックD）: 支持率の変化速度が 0.5%/分 以上 → 最強シグナル。相手候補に最優先で引き上げる
- トレンド逸脱（ロジックC）: 当日初回比で支持率 +5% 以上（5〜12番人気） → 相手候補の主要選定基準

### グループ2 — 相手候補選定の補強シグナル（同時発生で確度を高める）
- 支持率急増（ロジックA）: 短時間で支持率 +2% 以上（4番人気以下） → 単発でも相手候補候補。C・D と同時なら確信度大
- フェーズ逸脱[10分前]（ロジックE）: 発走直前の重要シグナル。相手候補の評価を1段上げる根拠
- フェーズ逸脱[30分前]（ロジックE）: 継続的な資金流入の証拠。相手候補の確度を高める
- フェーズ逸脱[朝]（ロジックE）: 長時間継続の支持。補強材料として評価

### グループ3 — 荒れレース判定専用（個別馬の verdict には影響させないこと）
- 順位乖離 / 順位乖離[拡大中] / 順位乖離[解消中]（ロジックB）: レース全体の混戦度合いを示す。\`is_chaotic_race\` の判断材料とする
- オッズ断層[凝縮] / オッズ断層[拡散]（ロジックF）: 断層位置で中穴ゾーンを定義（Step 1 で使用）。断層の凝縮/拡散は荒れ判定の補助材料にもなる

## 各馬のオッズ推移

${horsesData}

## アラート履歴

${alertsData}
※ アラートが無い場合は「なし」

## 分析指示
上記データから、オッズの推移軌跡・モメンタム・馬間の資金移動を多角的に分析し、以下のJSON形式で出力してください。

【判断のロジック】〈6つの観点〉

1. [直前] タグの優先
   - [直前] タグ（発走1時間以内）のデータが判断の主材料。[参考] タグは変化量の基準点として参照するのみ
   - データが [参考] しかない場合は「まだ層Bのデータが少ない」旨を trend_evidence に記載する

2. トレンド形状
   - 継続下落中：市場が継続的に支持を積み上げている → 相手候補の有力シグナル
   - 急落後安定：一時的な大口資金の可能性あり。安定継続なら信頼できる
   - 反発中（下落→上昇）：一度集まった支持が離れた。要注意
   - 直前急落：発走直前の情報流入の可能性。[直前] タグのデータほど重要

3. モメンタム（加速・減速）
   - 前回比列を参照し、下落が加速しているか減速・停止しているか判断する
   - [直前] 区間で加速中：市場の確信が強まっている

4. 支持の安定性
   - 推移が一方向に単調：市場コンセンサスが明確（信頼性高）
   - 推移が上下にノイジー：投機的・不安定（信頼性低）

5. 馬間の連動性
   - ある馬のオッズが下がる時間帯に別の馬が上がっていないか確認する
   - 連動している場合は資金移動が起きており、受け皿側の馬に注目

6. 複勝推移の活用（相手候補・対象外の判断に直結）
   - 単勝・複勝ともに下落中 → 市場全体が支持。相手候補として最優先評価
   - 複勝だけが緩やかに下落し、単勝は横ばい/高水準 → 相手候補として評価
   - 単勝・複勝ともに上昇中 → 対象外候補（人気離れの複合シグナル）

【重要】データに含まれる全馬について horses 配列にエントリを作成すること。
「対象外」と判断した馬であっても、なぜ外してよいかの根拠を trend_evidence に記載すること。
データが少ない・推移が不安定な馬は verdict を "対象外" とし、その旨を comment に明記すること。

必ず以下のJSON構造のみを出力してください。Markdownのコードブロック（\`\`\`json）や余計な解説文は一切含めず、純粋なJSON文字列として出力すること。

{
  "horses": [
    {
      "number": 馬番(数値),
      "name": "馬名",
      "verdict": "軸候補" または "相手候補" または "対象外",
      "comment": "軸候補の場合は中穴ゾーンである根拠（断層位置・人気順位）を、相手候補の場合は採用したシグナル（C/D/A/E）を、対象外の場合は外してよい理由を2〜4文で。単勝・馬単・三連単での役割に言及すること",
      "trend_evidence": "具体的な推移データの根拠を3〜5文で。時刻・数値・前回比・[直前]/[参考] タグの区間・他馬との連動性を交えて記述すること"
    }
  ],
  "trend_summary": "レース全体で観察された資金フロー・トレンドの転換点・市場コンセンサスの強弱を3〜5文で詳述。具体的な時刻・馬番・数値を使い抽象的な表現は避けること",
  "summary": "全体所感と今回の軸（中穴）× 相手（トレンド系）による単勝・馬単・三連単の戦略を2〜3文で",
  "confidence_score": データの充実度と動向の明確さから判断した推奨確度(1-100の整数),
  "is_chaotic_race": グループ3のシグナル（B・Fアラート）から判断した荒れレース可能性(true または false),
  "chaotic_note": "B・Fアラートの発生状況と荒れ判定の根拠を1〜2文で。シグナルなしの場合は「なし」と記述すること"
}

根拠が薄い・データ不足の場合は trend_evidence と trend_summary にその旨を明記してください。`;

    const modelParam = req.nextUrl.searchParams.get('model') ?? DEFAULT_MODEL;
    const GEMINI_MODEL = (ALLOWED_MODELS as readonly string[]).includes(modelParam) ? modelParam : DEFAULT_MODEL;
    const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY!);
    const model = genAI.getGenerativeModel({
      model: GEMINI_MODEL,
      systemInstruction: systemPrompt,
      generationConfig: {
        responseMimeType: 'application/json',
        // @ts-expect-error thinkingConfig は SDK 型定義未反映だが API はサポート済み
        thinkingConfig: { thinkingBudget: 1024 },
      },
    });

    const result = await model.generateContent(userPrompt);
    const text = result.response.text();
    const parsed = JSON.parse(text);
    return NextResponse.json({ ...parsed, model: GEMINI_MODEL });
  } catch (e) {
    console.warn('AI分析に失敗しました', e);
    return NextResponse.json({ error: 'AI分析に失敗しました' }, { status: 500 });
  }
}
