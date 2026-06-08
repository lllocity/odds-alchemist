import { parse } from 'node-html-parser';

const YAHOO_BASE = 'https://sports.yahoo.co.jp/keiba/race';
const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

type HorseInfo = {
  horseNumber: string;
  horseName: string;
  jockey: string;
  weight: number | null;
  weightDiff: number | null;
  prevRaceName: string;
  prevRaceResult: string;
};

type DistanceStat = { wins: number; second: number; third: number; other: number };

export function extractRaceId(oddsUrl: string): string | null {
  return oddsUrl.match(/\/(\d{10})(?:[/?#]|$)/)?.[1] ?? null;
}

async function fetchHtml(url: string): Promise<string> {
  const res = await fetch(url, {
    headers: {
      'User-Agent': UA,
      'Accept': 'text/html,application/xhtml+xml',
      'Accept-Language': 'ja,en-US;q=0.7,en;q=0.3',
    },
    cache: 'no-store',
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
  return res.text();
}

function parseWeight(s: string): { weight: number | null; weightDiff: number | null } {
  const m = s.match(/(\d{3,4})\(([+-]\d+)\)/);
  if (m) return { weight: +m[1], weightDiff: +m[2] };
  const n = +s.trim();
  return { weight: (!isNaN(n) && n >= 300 && n <= 700) ? n : null, weightDiff: null };
}

// "0.0.0.2" や "0-0-0-2" 形式の着別度数をパース
function parseStat(s: string): DistanceStat | null {
  const m = s.replace(/[()（）\s]/g, '').match(/^(\d+)[.\-](\d+)[.\-](\d+)[.\-](\d+)$/);
  if (!m) return null;
  return { wins: +m[1], second: +m[2], third: +m[3], other: +m[4] };
}

// th 要素のテキストから列インデックスマップを構築
function buildColMap(ths: string[]): Record<string, number> {
  const map: Record<string, number> = {};
  ths.forEach((t, i) => {
    const text = t.trim();
    if (text === '馬番') map.horseNum = i;
    if (text === '馬名') map.horseName = i;
    if (text === '騎手' || text === '騎手名') map.jockey = i;
    if (text === '馬体重') map.weight = i;
    if (text.startsWith('前走') && text.includes('レース')) map.prevRace = i;
    if (text.startsWith('前走') && text.includes('着')) map.prevResult = i;
    // 距離列: "芝1600m" "ダ1200m" など
    if (/^[芝ダ][1-9]\d{3}m$/.test(text)) map[text] = i;
  });
  return map;
}

/** 基本出馬表から馬番・馬名・騎手・馬体重を取得 */
async function fetchBasicDenma(raceId: string): Promise<{
  raceDistance: string;
  horses: Map<string, HorseInfo>;
  nameToNumber: Map<string, string>;
}> {
  const html = await fetchHtml(`${YAHOO_BASE}/denma/${raceId}`);
  const root = parse(html);

  const raceDistance = root.text.match(/[芝ダ][1-9]\d{3}m/)?.[0] ?? '';
  const horses = new Map<string, HorseInfo>();
  const nameToNumber = new Map<string, string>();

  for (const table of root.querySelectorAll('table')) {
    const rows = table.querySelectorAll('tr');
    if (rows.length < 3) continue;

    // ヘッダー行から列インデックスを特定
    let colMap: Record<string, number> = {};
    for (const row of rows) {
      const ths = row.querySelectorAll('th');
      if (ths.length < 4) continue;
      colMap = buildColMap(ths.map(h => h.text.trim()));
      if ('horseNum' in colMap) break;
    }
    if (!('horseNum' in colMap)) continue;

    for (const row of rows) {
      const tds = row.querySelectorAll('td');
      if (tds.length < 5) continue;
      const texts = tds.map(c => c.text.trim().replace(/\s+/g, ' '));

      const hn = texts[colMap.horseNum] ?? '';
      if (!/^\d{1,2}$/.test(hn) || +hn < 1 || +hn > 18 || horses.has(hn)) continue;

      const horseName = 'horseName' in colMap ? (texts[colMap.horseName] ?? '') : '';
      const jockey = 'jockey' in colMap ? (texts[colMap.jockey] ?? '') : '';
      const { weight, weightDiff } = parseWeight('weight' in colMap ? (texts[colMap.weight] ?? '') : '');

      const info: HorseInfo = { horseNumber: hn, horseName, jockey, weight, weightDiff, prevRaceName: '', prevRaceResult: '' };
      horses.set(hn, info);
      if (horseName) nameToNumber.set(horseName, hn);
    }

    if (horses.size > 0) break;
  }

  return { raceDistance, horses, nameToNumber };
}

/** 詳細出馬表（detail=1）から前走情報を取得してマージ */
async function mergeDetailDenma(raceId: string, horses: Map<string, HorseInfo>): Promise<void> {
  const html = await fetchHtml(`${YAHOO_BASE}/denma/${raceId}?detail=1`);
  const root = parse(html);

  for (const table of root.querySelectorAll('table')) {
    const rows = table.querySelectorAll('tr');
    if (rows.length < 3) continue;

    let colMap: Record<string, number> = {};
    for (const row of rows) {
      const ths = row.querySelectorAll('th');
      if (ths.length < 4) continue;
      colMap = buildColMap(ths.map(h => h.text.trim()));
      if ('horseNum' in colMap) break;
    }
    if (!('horseNum' in colMap)) continue;

    for (const row of rows) {
      const tds = row.querySelectorAll('td');
      if (tds.length < 4) continue;
      const texts = tds.map(c => c.text.trim().replace(/\s+/g, ' '));

      const hn = texts[colMap.horseNum] ?? '';
      const info = horses.get(hn);
      if (!info) continue;

      if ('prevRace' in colMap && texts[colMap.prevRace]) info.prevRaceName = texts[colMap.prevRace];
      if ('prevResult' in colMap && texts[colMap.prevResult]) info.prevRaceResult = texts[colMap.prevResult];

      // ヘッダーで列が特定できない場合はテキストパターンで補完
      if (!info.prevRaceResult || !info.prevRaceName) {
        for (const text of texts) {
          if (!info.prevRaceResult && /^\d{1,2}着$/.test(text)) info.prevRaceResult = text;
          if (!info.prevRaceName && text.length >= 4 && !text.includes('着') && !/^\d/.test(text)) {
            if (/\(G[I123V]+\)/.test(text) || (/[ァ-ヶ一-龥]/.test(text) && /[杯典賞Cカップ]/.test(text))) {
              info.prevRaceName = text;
            }
          }
        }
      }
    }

    if ([...horses.values()].some(h => h.prevRaceResult || h.prevRaceName)) break;
  }
}

/** 距離別成績テーブルから今走距離の着別度数を取得 */
async function fetchDistanceStats(
  raceId: string,
  raceDistance: string,
  nameToNumber: Map<string, string>
): Promise<Map<string, DistanceStat>> {
  const result = new Map<string, DistanceStat>();
  if (!raceDistance) return result;

  const html = await fetchHtml(`${YAHOO_BASE}/achievement/distance/${raceId}`);
  const root = parse(html);

  // achievement/distance は「枠番・馬番・馬名・芝1400m・芝1600m・...」の1テーブル構造
  for (const table of root.querySelectorAll('table')) {
    const rows = table.querySelectorAll('tr');
    if (rows.length < 3) continue;

    let colMap: Record<string, number> = {};
    for (const row of rows) {
      const ths = row.querySelectorAll('th');
      if (ths.length < 3) continue;
      colMap = buildColMap(ths.map(h => h.text.trim()));
      if ('horseNum' in colMap && raceDistance in colMap) break;
    }
    if (!('horseNum' in colMap) || !(raceDistance in colMap)) continue;

    const distCol = colMap[raceDistance];
    for (const row of rows) {
      const tds = row.querySelectorAll('td');
      if (tds.length <= distCol) continue;
      const texts = tds.map(c => c.text.trim().replace(/\s+/g, ''));

      let hn = texts[colMap.horseNum] ?? '';
      // 馬番で特定できない場合は馬名で補完
      if (!hn || !/^\d{1,2}$/.test(hn)) {
        const nameText = 'horseName' in colMap ? texts[colMap.horseName] : '';
        hn = nameToNumber.get(nameText) ?? '';
      }
      if (!hn || result.has(hn)) continue;

      const stat = parseStat(texts[distCol] ?? '');
      if (stat) result.set(hn, stat);
    }

    if (result.size > 0) break;
  }

  return result;
}

/** プロンプト用の出馬表情報セクションを生成 */
function buildSection(
  raceDistance: string,
  horses: Map<string, HorseInfo>,
  distanceStats: Map<string, DistanceStat>
): string {
  if (horses.size === 0) return '';

  const lines: string[] = [`## 出馬表情報（今走: ${raceDistance || '不明'}）`];

  for (const h of [...horses.values()].sort((a, b) => +a.horseNumber - +b.horseNumber)) {
    const parts: string[] = [];

    if (h.jockey) parts.push(`騎手: ${h.jockey}`);

    const prev = [h.prevRaceName, h.prevRaceResult].filter(Boolean).join(' ');
    parts.push(`前走: ${prev || '不明'}`);

    if (h.weight !== null) {
      const diff = h.weightDiff == null || h.weightDiff === 0 ? '変化なし'
        : h.weightDiff > 0 ? `+${h.weightDiff}kg` : `${h.weightDiff}kg`;
      parts.push(`馬体重: ${h.weight}kg（${diff}）`);
    }

    const stat = distanceStats.get(h.horseNumber);
    if (stat && raceDistance) {
      parts.push(`${raceDistance}実績: ${stat.wins}-${stat.second}-${stat.third}-${stat.other}`);
    }

    lines.push(`【${h.horseNumber}番 ${h.horseName}】 ${parts.join(' / ')}`);
  }

  return lines.join('\n');
}

/**
 * AI分析リクエスト時にオッズURLからレースIDを抽出し、
 * 出馬表・距離別成績をオンデマンドでスクレイピングしてプロンプト用テキストを返す。
 * 失敗時は空文字を返す（分析処理は継続される）。
 */
export async function fetchSupplementalSection(oddsUrl: string): Promise<string> {
  const raceId = extractRaceId(oddsUrl);
  if (!raceId) return '';

  try {
    const { raceDistance, horses, nameToNumber } = await fetchBasicDenma(raceId);
    if (horses.size === 0) return '';

    // 詳細出馬表と距離別成績を並列取得
    const [distanceStats] = await Promise.all([
      fetchDistanceStats(raceId, raceDistance, nameToNumber).catch(e => {
        console.warn('距離別成績の取得に失敗しました', e);
        return new Map<string, DistanceStat>();
      }),
      mergeDetailDenma(raceId, horses).catch(e => {
        console.warn('詳細出馬表の取得に失敗しました', e);
      }),
    ]);

    return buildSection(raceDistance, horses, distanceStats);
  } catch (e) {
    console.warn('出馬表スクレイピングに失敗しました', e);
    return '';
  }
}
