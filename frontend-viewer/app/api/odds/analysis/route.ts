import { NextRequest, NextResponse } from 'next/server';
import { GoogleGenerativeAI } from '@google/generative-ai';
import { getOddsData, getAlerts } from '@/lib/sheets';
import { fetchSupplementalSection } from '@/lib/scrapeRaceData';

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

      // 前レコードとの時刻差で層A/B を判定（負の差分は朝データの逆転）
      const diff = (r.detectedAtMinutes !== null && prevMinutes !== null)
        ? r.detectedAtMinutes - prevMinutes
        : null;
      const layerTag = (diff === null || diff < 0 || diff >= 20) ? '[参考]' : '[直前]';

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
    const [oddsRows, alertRows, supplementalSection] = await Promise.all([
      getOddsData(),
      getAlerts(),
      fetchSupplementalSection(url),
    ]);

    const urlRows = oddsRows.filter(row => row[1] === url);
    if (urlRows.length === 0) {
      return NextResponse.json({ error: '指定されたURLのデータが見つかりません' }, { status: 404 });
    }

    const raceName = urlRows[0][2] ?? url;
    const horsesData = buildHorsesData(oddsRows, url, alertRows);
    const alertsData = buildAlertsData(alertRows, url);

    const systemPrompt = `あなたは日本の競馬のオッズ動向を分析する専門家です。
提供されるデータをもとに、市場の意図や資金の流れを読み解いて各馬を評価してください。
回答は指示されたJSON形式のみを出力してください。前置き・後書きは不要です。`;

    const userPrompt = `以下は【${raceName}】のオッズ推移データです。

## データの見方
- 単勝オッズ: 数値が低いほど支持率が高い（人気馬）
- [直前] タグ: 発走1時間以内のデータ（判断の主材料）
- [参考] タグ: それ以前のデータ（変化量の基準点として参照）
- 「*」マーク: その時点でアラートが発生していることを示す
- ▶ トレンド行: 直近5件の動向分類と合計変化量

## 評価の定義
- 軸候補: 人気上位でも最下位でもない中穴ゾーンで、[直前] 区間のオッズが安定または下落している馬（1〜2頭）
- 相手候補: [直前] 区間でオッズが下落傾向、またはアラートが出ている馬（3〜5頭）
- 対象外: 明確な支持の根拠がない馬

## 各馬のオッズ推移

${horsesData}

## アラート履歴

${alertsData}
※ アラートが無い場合は「なし」

${supplementalSection}

## 分析指示
オッズ推移・アラート・出馬表情報（騎手・前走・馬体重・距離実績）を総合して全馬を評価してください。
[直前] タグのデータを主材料とし、[参考] は変化量の基準点として参照してください。

全馬について horses 配列にエントリを作成し、以下のJSON形式のみで出力してください:

{
  "horses": [
    {
      "number": 馬番(数値),
      "name": "馬名",
      "verdict": "軸候補" または "相手候補" または "対象外",
      "comment": "2〜3文。オッズの動き・アラート・出馬表情報を根拠として使うこと。対象外の場合もその理由を書くこと"
    }
  ],
  "summary": "レース全体のオッズ動向と評価の概要を2〜3文で"
}`;

    // ?debug=true のときは Gemini を呼ばずにプロンプト本文を返す（検証用）
    if (req.nextUrl.searchParams.get('debug') === 'true') {
      const { extractRaceId } = await import('@/lib/scrapeRaceData');
      const debugRaceId = extractRaceId(url);
      return NextResponse.json({ systemPrompt, userPrompt, debugRaceId, supplementalSection });
    }

    // ?debug=scrape のときは detail=1 ページの診断情報を返す（前走情報調査用）
    if (req.nextUrl.searchParams.get('debug') === 'scrape') {
      const { extractRaceId, diagnoseDetailDenma } = await import('@/lib/scrapeRaceData');
      const raceId = extractRaceId(url);
      const diagnosis = raceId ? await diagnoseDetailDenma(raceId) : { error: 'raceId の抽出に失敗' };
      return NextResponse.json({ raceId, diagnosis });
    }

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
