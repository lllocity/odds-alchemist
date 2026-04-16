import { NextResponse } from 'next/server';
import { getOddsData } from '@/lib/sheets';
import { OddsHistoryItem } from '@/app/types/oddsHistory';

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const url = searchParams.get('url');
  const horseName = searchParams.get('horseName');

  if (!url || !horseName) {
    return NextResponse.json({ error: 'url と horseName パラメータが必要です' }, { status: 400 });
  }

  const rows = await getOddsData();

  // B列 === url かつ E列 === horseName でフィルタ
  const filtered = rows.filter((row) => row[1] === url && row[4] === horseName);

  // A列（取得日時）の昇順にソート
  filtered.sort((a, b) => (a[0] ?? '').localeCompare(b[0] ?? ''));

  const items: OddsHistoryItem[] = filtered.map((row) => ({
    detectedAt: row[0] ?? '',
    winOdds: row[5] ? parseFloat(row[5]) || null : null,
    placeOddsMin: row[6] ? parseFloat(row[6]) || null : null,
    placeOddsMax: row[7] ? parseFloat(row[7]) || null : null,
  }));

  return NextResponse.json(items);
}
