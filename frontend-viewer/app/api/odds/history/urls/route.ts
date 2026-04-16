import { NextResponse } from 'next/server';
import { getOddsData } from '@/lib/sheets';

export async function GET() {
  const rows = await getOddsData();

  // B列（index 1）をユニーク化して返す
  const urls = [...new Set(rows.map((row) => row[1]).filter(Boolean))];

  return NextResponse.json(urls);
}
