import { NextRequest, NextResponse } from 'next/server';
import { GoogleGenerativeAI } from '@google/generative-ai';
import { getOddsData, getAlerts } from '@/lib/sheets';

export const maxDuration = 60;

function toFloat(s: string | undefined): number | null {
  if (!s) return null;
  const n = parseFloat(s);
  return isNaN(n) ? null : n;
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
  type HorseRow = { time: string; winOdds: number | null; placeMin: number | null; placeMax: number | null };
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

    const lines: string[] = [
      `【${horse.number}番 ${horse.name}】`,
      '時刻,単勝,複勝下限,複勝上限,単勝変化率,単勝前回比,複勝前回比',
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

      lines.push(
        `${r.time},${r.winOdds?.toFixed(1) ?? '-'},${r.placeMin?.toFixed(1) ?? '-'},${r.placeMax?.toFixed(1) ?? '-'},${cumRate},${velStr},${placeVelStr}${alertSuffix}`
      );
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
- 本命は最大2頭まで
- 本命と対抗の合計は最大3頭まで（本命1頭なら対抗は最大2頭、本命2頭なら対抗は最大1頭）
- 3着紐は最大5頭まで`;

    const userPrompt = `以下は【${raceName}】のオッズ推移データです。

## データの見方
- 単勝オッズ: 1着的中時の払戻倍率。数値が低いほど支持率が高い（人気馬）
- 複勝オッズ（下限〜上限）: 3着以内的中時の払戻倍率の範囲
- 単勝変化率: 最初の取得値を基準とした単勝オッズの変化率（マイナス＝下落＝支持増）
- 単勝前回比: 前回取得値との単勝オッズの差（マイナス＝下落＝支持増）
- 複勝前回比: 前回取得値との複勝下限の差（マイナス＝下落＝3着以内の支持増）
- 「*」マーク: その時点でアラートが発生していることを示す
- ▶ トレンド行: 直近5件の動向分類と合計変化量

## verdict 判断の基本原則：オッズ水準 × トレンド強度の掛け合わせ
verdict はオッズの「絶対水準（最終単勝オッズが何倍か）」と「トレンド強度（どれだけ動いたか）」の両方を組み合わせて決定すること。トレンドだけで判断しないこと。

verdict の定義:
- 本命: 1着を狙う最有力馬（軸）
- 対抗: 三連複の軸2頭目、ワイドの相手筆頭（本命と組み合わせて三連複の軸を形成する）
- 3着紐: 3着以内に絡む可能性がある馬（三連系の紐向き）
- 消し: 馬券から外してよい馬（データ不足・推移不安定・支持なし）

verdict の選択プロセス（この順番で決定すること）:
【Step 1】全馬のデータを分析し、オッズ水準×トレンド強度でランク付けする
【Step 2】本命を選ぶ（最大2頭）: 最も根拠の強い馬を1〜2頭選ぶ。根拠が薄ければ1頭でもよい
【Step 3】対抗を選ぶ（本命と合わせて最大3頭）: 本命が1頭なら対抗は最大2頭、本命が2頭なら対抗は最大1頭
【Step 4】3着紐を選ぶ（最大5頭）: 3着以内に絡む可能性がある馬を選ぶ
【Step 5】残りはすべて「消し」にする
※ 各Stepで上限に達したら追加しないこと。上限まで無理に埋める必要はない。

掛け合わせの目安:
- 低オッズ（15倍未満）＋強いトレンド → 本命の最有力（三連複の軸筆頭・ワイドの基準馬）
- 低オッズ（15倍未満）＋弱いトレンド → 対抗 または 3着紐（三連複のヒモ候補）
- 中オッズ（15倍以上〜30倍未満）＋強いトレンド → 本命として十分あり（三連複の軸・ワイド対象）
- 中オッズ（15倍以上〜30倍未満）＋弱いトレンド → 3着紐（三連複のヒモ・ワイド押さえ）
- 高オッズ（30倍以上）＋強いトレンド・複数アラート → 対抗 または 3着紐。本命とする場合はその根拠を comment に明記
- 高オッズ（30倍以上）＋弱いトレンド → 消し

※「トレンドが下落している」だけで本命にしないこと。150倍→100倍への下落は「下落トレンド」だが依然として市場の評価は低く、複数の強い根拠が必要。

複勝推移との組み合わせ:
- 単勝下落 + 複勝も下落 → 本命の信頼度UP（市場全体で評価）
- 単勝横ばい/高い + 複勝下落 → 対抗として優先評価（「飛ぶよりは来る」）
- 単勝上昇 + 複勝上昇 → 消し（複合的な人気離れシグナル）
- 単勝高い + 複勝のみ下落 → 3着紐として評価

## アラートの種類（いずれも買いの根拠として使うべきシグナル）

### 資金流入シグナル（仕込み検知）
- 支持率急増: 短時間で支持率（1/オッズ）が+2%以上急増（4番人気以下限定）→ 玄人筋・関係者の仕込みの可能性
- 支持率加速: 支持率の変化速度が0.5%/分以上で加速中（4番人気以下限定）→ 単発急増より「流れ」があり信頼度高め。急増と同時発生は最強シグナル

### 単複乖離シグナル（複勝・ワイド向き馬の識別）
- 順位乖離: 単勝人気順位と複勝人気順位の差が3以上 → 「勝ちきれないが来る」市場評価。複勝・ワイド・三連複の軸として最優先
- 順位乖離[拡大中]: 乖離が前回よりさらに拡大 → 複勝への資金流入が継続中。買い継続のシグナル
- 順位乖離[解消中]: 乖離が前回より縮小（単勝が追いついてきた）→ 単勝・馬単への切り替えを検討

### トレンド逸脱シグナル（当日・直前の情報先行）
- トレンド逸脱: 当日初回比で支持率+5%以上（5〜12番人気の中穴・大穴帯限定）→ 大幅な評価転換。三連複・ワイドの相手候補
- フェーズ逸脱[朝]: 朝の最初のオッズからの支持率増加+5%以上 → 長時間継続の資金流入。信頼度が高い
- フェーズ逸脱[30分前]: 発走30分前基準値から+5%以上 → 情報筋の直前仕込みの可能性。フェーズ逸脱[朝]との同時検知で確信度UP
- フェーズ逸脱[10分前]: 発走10分前基準値から+5%以上 → 最終局面の重要シグナル。厩舎・直前情報が反映された可能性が高い

### 断層シグナル（レース全体の資金分布変化）
- オッズ断層[凝縮]: 断層位置が上位に移動（特定馬への絞り込みが進行）→ 断層内の上位馬を本命視。三連複の軸を絞り込める
- オッズ断層[拡散]: 断層位置が下位に移動（混戦化・下位馬への資金流入）→ 穴狙い戦略へ切り替え。三連複のヒモを広めに取る

## 各馬のオッズ推移

${horsesData}

## アラート履歴

${alertsData}
※ アラートが無い場合は「なし」

## 分析指示
上記データから、オッズの推移軌跡・モメンタム・馬間の資金移動を多角的に分析し、以下のJSON形式で出力してください。

【判断のロジック】〈オッズ水準とトレンドを掛け合わせた7つの観点〉

1. トレンド形状（上記の掛け合わせ原則と併用すること）
   - 継続下落中：市場が継続的に支持を積み上げている → 最有力シグナル
   - 急落後安定：一時的な大口資金の可能性あり。安定継続なら信頼できる
   - 反発中（下落→上昇）：一度集まった支持が離れた。要注意
   - 直前急落：発走直前の情報流入の可能性。データ後半の動きほど重要

2. モメンタム（加速・減速）
   - 前回比列を参照し、下落が加速しているか減速・停止しているか判断する
   - 直近で加速中：市場の確信が強まっている
   - 直近で減速・横ばい：支持がピークアウトした可能性

3. 支持の安定性
   - 推移が一方向に単調：市場コンセンサスが明確（信頼性高）
   - 推移が上下にノイジー：投機的・不安定（信頼性低）

4. 馬間の連動性
   - ある馬のオッズが下がる時間帯に別の馬が上がっていないか確認する
   - 連動している場合は資金移動が起きており、受け皿側の馬に注目

5. 変化のタイミング
   - 早期からの継続下落：安定したファン・専門家の支持
   - 直前（最終数点）での急変：当日の状態・情報に基づく可能性が高く重要

6. 複勝推移の活用（対抗・3着紐の判断に直結）
   - 単勝・複勝ともに下落中 → 本命候補の信頼度UP（市場全体が支持）
   - 複勝だけが緩やかに下落し、単勝は横ばい/高水準 → 対抗・3着紐候補（「飛ぶよりは来る」という市場評価。三連複・ワイドでの押さえが有効）
   - 単勝が下落中で複勝は横ばい → 勝ち切りに期待される馬（本命寄りで評価）
   - 単勝・複勝ともに上昇中 → 消し候補（人気離れの複合シグナル）
   - 単勝は高いが複勝だけ下落 → 3着紐候補（単勝人気は低いが3着以内は評価されている穴馬）
   - 単勝人気と複勝人気が著しくずれている馬は穴人気の可能性

7. アラートの活用（重要）
   - アラートは「異常」ではなく「買いの根拠」として扱うこと
   - アラートが発生した馬を "消し" にする場合は、オッズ水準やトレンドと照らし合わせて慎重に判断すること。アラートは買いのヒントであって、消しを禁止する絶対条件ではない
   - 複数のアラートが同一馬に集中している場合は本命として優先的に評価する

   【verdict判定への影響指針】
   - 支持率急増 / 支持率加速: その馬を3着紐以上に押し上げる強いシグナル。同時発生なら本命候補
   - 順位乖離 / 順位乖離[拡大中]: verdict は「対抗」または「3着紐」に誘導。複勝・ワイド軸として評価
   - 順位乖離[解消中]: 複勝軸評価から切り替え検討。verdict は「本命」または「対抗」寄り
   - フェーズ逸脱[10分前]: 最重要シグナル。オッズ水準に関わらず3着紐以上に引き上げる
   - フェーズ逸脱[30分前] / フェーズ逸脱[朝]: 継続的資金流入の証拠。verdict を1段階上方修正する根拠
   - トレンド逸脱: 中穴・大穴帯（5〜12番人気）の馬を3着紐候補に引き上げる
   - オッズ断層[凝縮]: 断層より上位の馬群の中で最も支持された馬を本命・対抗に優先
   - オッズ断層[拡散]: 断層より下位の馬も3着紐候補に追加。本命の絞り込みは慎重に

【重要】データに含まれる全馬について horses 配列にエントリを作成すること。
「消し」と判断した馬であっても、なぜ消してよいかの根拠を trend_evidence に記載すること。
データが少ない・推移が不安定な馬は verdict を "消し" とし、その旨を comment に明記すること。

必ず以下のJSON構造のみを出力してください。Markdownのコードブロック（\`\`\`json）や余計な解説文は一切含めず、純粋なJSON文字列として出力すること。

{
  "horses": [
    {
      "number": 馬番(数値),
      "name": "馬名",
      "verdict": "本命" または "対抗" または "3着紐" または "消し",
      "comment": "推移の観点から見たこの馬の評価と買い目における位置づけを2〜4文で。三連複での役割（軸/ヒモ）・ワイドの相手として有効かどうか・外してよいかを明示すること",
      "trend_evidence": "具体的な推移データの根拠を3〜5文で。時刻・数値・前回比・他馬との連動性を交えて記述すること"
    }
  ],
  "trend_summary": "レース全体で観察された資金フロー・トレンドの転換点・市場コンセンサスの強弱を3〜5文で詳述。具体的な時刻・馬番・数値を使い抽象的な表現は避けること",
  "summary": "全体所感と今回の三連複・ワイドの買い目戦略の方向性を2〜3文で",
  "confidence_score": データの充実度と動向の明確さから判断した推奨確度(1-100の整数)
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
