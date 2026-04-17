import { NextResponse } from 'next/server';
import { getOddsData } from '@/lib/sheets';

export async function GET() {
  const rows = await getOddsData();

  // B列(URL) と C列(レース名) のペアを作成し、URLでユニーク化
  const seen = new Set<string>();
  const result: { url: string; raceName: string }[] = [];
  for (const row of rows) {
    const url = row[1] ?? '';
    const raceName = row[2] ?? '';
    if (url && !seen.has(url)) {
      seen.add(url);
      result.push({ url, raceName });
    }
  }

  return NextResponse.json(result);
}
