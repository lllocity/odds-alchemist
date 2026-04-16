import { NextResponse } from 'next/server';
import { getOddsData } from '@/lib/sheets';
import { HorseOption } from '@/app/types/oddsHistory';

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const url = searchParams.get('url');

  if (!url) {
    return NextResponse.json({ error: 'url パラメータが必要です' }, { status: 400 });
  }

  const rows = await getOddsData();

  // B列 === url でフィルタし、D列(馬番)・E列(馬名)をユニーク化
  const seen = new Set<string>();
  const horses: HorseOption[] = [];

  for (const row of rows) {
    if (row[1] !== url) continue;
    const horseNumber = row[3];
    const horseName = row[4];
    if (!horseNumber || !horseName) continue;
    if (seen.has(horseNumber)) continue;
    seen.add(horseNumber);
    horses.push({ horseNumber: parseInt(horseNumber, 10), horseName });
  }

  horses.sort((a, b) => a.horseNumber - b.horseNumber);

  return NextResponse.json(horses);
}
