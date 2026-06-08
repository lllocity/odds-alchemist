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

/** "レーベンスティール 牡6/鹿毛" → "レーベンスティール"（性齢・毛色のサフィックスを除去） */
function cleanHorseName(raw: string): string {
  return raw.replace(/\s+(?:牡|牝|せん|騸)\d+.*$/, '').trim();
}

/** "戸崎 圭太58.0" → "戸崎 圭太"（斤量の数値を除去） */
function cleanJockeyName(raw: string): string {
  return raw.replace(/\s*\d+\.\d+$/, '').trim();
}

// "496(+4)" "496(-14)" "496(0)" "496(-)" に対応
function parseWeight(s: string): { weight: number | null; weightDiff: number | null } {
  const withSign = s.match(/(\d{3,4})\(([+-]\d+)\)/);
  if (withSign) return { weight: +withSign[1], weightDiff: +withSign[2] };
  const zero = s.match(/(\d{3,4})\(0\)/);
  if (zero) return { weight: +zero[1], weightDiff: 0 };
  const noPrev = s.match(/(\d{3,4})\(-\)/);
  if (noPrev) return { weight: +noPrev[1], weightDiff: null };
  const n = +s.trim();
  return { weight: (!isNaN(n) && n >= 300 && n <= 700) ? n : null, weightDiff: null };
}

// "0.0.0.2" や "0-0-0-2" 形式の着別度数をパース
function parseStat(s: string): DistanceStat | null {
  const m = s.replace(/[()（）\s]/g, '').match(/^(\d+)[.\-](\d+)[.\-](\d+)[.\-](\d+)$/);
  if (!m) return null;
  return { wins: +m[1], second: +m[2], third: +m[3], other: +m[4] };
}

// th または td のテキストから列インデックスマップを構築（includes マッチで柔軟に対応）
function buildColMap(headers: string[]): Record<string, number> {
  const map: Record<string, number> = {};
  headers.forEach((raw, i) => {
    const t = raw.replace(/[\s　]/g, ''); // 半角・全角スペースを除去
    if (t.includes('馬番') && !t.includes('馬名')) map.horseNum = i;
    if (t.includes('馬名') && !t.includes('馬番') && !t.includes('母')) map.horseName = i;
    if (t.includes('騎手')) map.jockey = i;
    if (t.includes('馬体重')) map.weight = i;
    if (t.includes('前走') && t.includes('レース')) map.prevRace = i;
    if (t.includes('前走') && (t.includes('着') || t.includes('結果'))) map.prevResult = i;
    // 距離列: "芝1600m" "ダ1200m" など（全角ｍも考慮）
    if (/^[芝ダ][1-9]\d{3}[mｍ]$/.test(t)) map[t.replace('ｍ', 'm')] = i;
  });
  return map;
}

// th → td の順でヘッダー行を探してcolMapを返す
function detectColMap(rows: ReturnType<typeof parse>['querySelectorAll'] extends (s: string) => infer R ? R : never): Record<string, number> {
  for (const row of rows) {
    // th 優先
    const ths = row.querySelectorAll('th');
    if (ths.length >= 4) {
      const m = buildColMap(ths.map(h => h.text.trim()));
      if ('horseNum' in m) return m;
    }
    // td でも試みる（Yahoo Sports は td を header として使う場合がある）
    const tds = row.querySelectorAll('td');
    if (tds.length >= 4) {
      const m = buildColMap(tds.map(h => h.text.trim()));
      if ('horseNum' in m) return m;
    }
  }
  return {};
}

/** 基本出馬表から馬番・馬名・騎手・馬体重を取得 */
async function fetchBasicDenma(raceId: string): Promise<{
  raceDistance: string;
  horses: Map<string, HorseInfo>;
  nameToNumber: Map<string, string>;
}> {
  const html = await fetchHtml(`${YAHOO_BASE}/denma/${raceId}`);
  const root = parse(html);

  // ページ全文からレース距離を抽出（複数パターンに対応）
  const text = root.text;
  const distanceMatch =
    text.match(/[芝ダ]\s*[1-9]\d{3}\s*[mｍ]/) ||
    text.match(/[1-9]\d{3}\s*[mｍ][（(][芝ダ]/);
  const raceDistance = (distanceMatch?.[0] ?? '').replace(/\s+/g, '').replace('ｍ', 'm');

  const horses = new Map<string, HorseInfo>();
  const nameToNumber = new Map<string, string>();

  for (const table of root.querySelectorAll('table')) {
    const rows = table.querySelectorAll('tr');
    if (rows.length < 3) continue;

    const colMap = detectColMap(rows);
    if (!('horseNum' in colMap)) continue;

    for (const row of rows) {
      const tds = row.querySelectorAll('td');
      if (tds.length < 5) continue;
      const texts = tds.map(c => c.text.trim().replace(/\s+/g, ' '));

      // 馬番を特定（colMap 優先、なければ先頭5列のパターンマッチ）
      let hnCol: number;
      if ('horseNum' in colMap) {
        hnCol = colMap.horseNum;
      } else {
        hnCol = texts.findIndex((t, i) => i < 5 && /^\d{1,2}$/.test(t) && +t >= 1 && +t <= 18);
      }
      if (hnCol < 0) continue;

      const hn = texts[hnCol] ?? '';
      if (!/^\d{1,2}$/.test(hn) || +hn < 1 || +hn > 18 || horses.has(hn)) continue;

      // 馬名・騎手・馬体重: colMap 優先、なければ馬番列からの相対位置で取得
      // 列構成: 枠(0), 馬番(1), 馬名(2), 性齢(3), 騎手(4), 斤量(5), 調教師(6), 父(7), 母(8), 馬体重(9), 人気(10)
      const horseName = cleanHorseName(
        ('horseName' in colMap)
          ? (texts[colMap.horseName] ?? '')
          : (hnCol + 1 < texts.length ? texts[hnCol + 1] : '')
      );

      const jockey = cleanJockeyName(
        ('jockey' in colMap)
          ? (texts[colMap.jockey] ?? '')
          : (hnCol + 3 < texts.length ? texts[hnCol + 3] : '')
      );

      const weightRaw = ('weight' in colMap)
        ? (texts[colMap.weight] ?? '')
        : (hnCol + 8 < texts.length ? texts[hnCol + 8] : '');

      const { weight, weightDiff } = parseWeight(weightRaw);

      const info: HorseInfo = { horseNumber: hn, horseName, jockey, weight, weightDiff, prevRaceName: '', prevRaceResult: '' };
      horses.set(hn, info);
      if (horseName) nameToNumber.set(horseName, hn);
    }

    if (horses.size > 0) break;
  }

  return { raceDistance, horses, nameToNumber };
}

/**
 * 詳細出馬表（detail=1）から前走情報を取得してマージ。
 *
 * ページは2テーブル構造:
 *   テーブル1: 馬エントリ行（2列）  row_i → horse i+1
 *   テーブル2: 詳細行（6列）        row0=サブヘッダ、row_i → horse i+1 の前走データ
 */
async function mergeDetailDenma(raceId: string, horses: Map<string, HorseInfo>): Promise<void> {
  const html = await fetchHtml(`${YAHOO_BASE}/denma/${raceId}?detail=1`);
  const root = parse(html);
  const tables = root.querySelectorAll('table');
  if (tables.length < 2) return;

  // テーブル1から馬番を出現順に収集
  const horseNumbersInOrder: string[] = [];
  for (const row of tables[0].querySelectorAll('tr')) {
    const tds = row.querySelectorAll('td');
    if (tds.length < 2) continue;
    const cell0 = tds[0].text.replace(/\s+/g, ' ').trim();
    // "1 1" や "1 2" → 末尾の数字が馬番
    const nums = cell0.match(/\d+/g);
    if (nums) horseNumbersInOrder.push(nums[nums.length - 1]);
  }
  if (horseNumbersInOrder.length === 0) return;

  // テーブル2: row0 がサブヘッダ、row1+ が各馬の詳細（同順）
  const table2Rows = tables[1].querySelectorAll('tr');

  // サブヘッダから「前走」列のインデックスを特定
  let prevRaceCol = 1;
  if (table2Rows.length > 0) {
    const subHeaderTexts = [...table2Rows[0].querySelectorAll('td, th')]
      .map(c => c.text.trim().replace(/\s+/g, ''));
    const idx = subHeaderTexts.findIndex(
      t => t === '前走' || (t.includes('前走') && !t.includes('前々') && !/[2-6]前/.test(t))
    );
    if (idx >= 0) prevRaceCol = idx;
  }

  let found = 0;
  for (let i = 1; i < table2Rows.length; i++) {
    const horseIdx = i - 1;
    if (horseIdx >= horseNumbersInOrder.length) break;

    const info = horses.get(horseNumbersInOrder[horseIdx]);
    if (!info) continue;

    const tds = table2Rows[i].querySelectorAll('td');
    if (tds.length <= prevRaceCol) continue;

    const cellText = tds[prevRaceCol].text.trim().replace(/\s+/g, ' ');
    // 実フォーマット: "2026/04/05 阪神 芝・右2000m 良 6大阪杯GI 492(+8)..."
    // トラック状態（良/稍重/重/不良）の直後に「着順+レース名略称」が続く
    const raceInfoMatch = cellText.match(/(?:良|稍重|重|不良)\s+(\d{1,2})([^\d\s（）()]{2,})/);
    if (raceInfoMatch) {
      info.prevRaceResult = raceInfoMatch[1] + '着';
      info.prevRaceName = raceInfoMatch[2];
    }
    found++;
  }

  // フォールバック: 旧パターン（テーブル構造が変わった場合）
  if (found === 0) {
    for (const table of tables) {
      for (const row of table.querySelectorAll('tr')) {
        const tds = row.querySelectorAll('td');
        if (tds.length < 5) continue;
        const texts = tds.map(c => c.text.trim().replace(/\s+/g, ' '));
        for (const t of texts) {
          // パターンマッチで "N着" を含む行を探して近くの馬情報と紐付けを試みる
          if (/^\d{1,2}着$/.test(t)) found++;
        }
      }
      if (found > 0) break;
    }
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

    const colMap = detectColMap(rows);
    const distCol = colMap[raceDistance] ?? -1;
    if (!('horseNum' in colMap) || distCol < 0) continue;

    for (const row of rows) {
      const tds = row.querySelectorAll('td');
      if (tds.length <= distCol) continue;
      const texts = tds.map(c => c.text.trim().replace(/\s+/g, ''));

      let hn = texts[colMap.horseNum] ?? '';
      if (!hn || !/^\d{1,2}$/.test(hn)) {
        // 馬名で補完
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
      const diff = h.weightDiff == null ? '前走データなし'
        : h.weightDiff === 0 ? '変化なし'
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
 * detail=1 ページの取得状況を診断して返す（デバッグ用）。
 * テーブル数・colMap の検出結果・先頭5行のサンプルを含む。
 */
export async function diagnoseDetailDenma(raceId: string): Promise<{
  httpStatus: number | null;
  httpError: string | null;
  tablesFound: number;
  targetTableFound: boolean;
  colMapDetected: Record<string, number>;
  headerCandidates: string[][];
  sampleDataRows: string[][];
  table2Preview: string[][];
}> {
  try {
    const res = await fetch(`${YAHOO_BASE}/denma/${raceId}?detail=1`, {
      headers: {
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'ja,en-US;q=0.7,en;q=0.3',
      },
      cache: 'no-store',
    });
    const httpStatus = res.status;
    if (!res.ok) {
      return { httpStatus, httpError: `HTTP ${res.status}`, tablesFound: 0, targetTableFound: false, colMapDetected: {}, headerCandidates: [], sampleDataRows: [], table2Preview: [] };
    }

    const html = await res.text();
    const root = parse(html);
    const tables = root.querySelectorAll('table');

    let targetTableFound = false;
    let colMapDetected: Record<string, number> = {};
    const headerCandidates: string[][] = [];
    const sampleDataRows: string[][] = [];

    for (const table of tables) {
      const rows = table.querySelectorAll('tr');
      if (rows.length < 2) continue;

      // 先頭3行のテキストをサンプルとして収集（100字まで）
      for (let i = 0; i < Math.min(3, rows.length); i++) {
        const cells = rows[i].querySelectorAll('th, td');
        if (cells.length > 0) {
          headerCandidates.push(cells.map(c => c.text.trim().replace(/\s+/g, ' ').slice(0, 100)));
        }
      }

      const colMap = detectColMap(rows);
      if ('horseNum' in colMap) {
        targetTableFound = true;
        colMapDetected = colMap;
        // 最初のデータ行を数件取得
        for (const row of rows) {
          const tds = row.querySelectorAll('td');
          if (tds.length < 3) continue;
          sampleDataRows.push(tds.map(c => c.text.trim().replace(/\s+/g, ' ').slice(0, 100)));
          if (sampleDataRows.length >= 5) break;
        }
        break;
      }
    }

    // テーブル2の前走列の実際の内容を確認するための追加診断
    const table2Preview: string[][] = [];
    if (tables.length >= 2) {
      const t2rows = tables[1].querySelectorAll('tr');
      for (let i = 0; i < Math.min(4, t2rows.length); i++) {
        const cells = t2rows[i].querySelectorAll('td, th');
        table2Preview.push(cells.map(c => c.text.trim().replace(/\s+/g, ' ').slice(0, 100)));
      }
    }

    return { httpStatus, httpError: null, tablesFound: tables.length, targetTableFound, colMapDetected, headerCandidates: headerCandidates.slice(0, 5), sampleDataRows, table2Preview };
  } catch (e) {
    return { httpStatus: null, httpError: String(e), tablesFound: 0, targetTableFound: false, colMapDetected: {}, headerCandidates: [], sampleDataRows: [], table2Preview: [] };
  }
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
