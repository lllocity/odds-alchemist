import { NextResponse } from 'next/server';
import { getAlerts } from '@/lib/sheets';
import { AlertHistoryItem } from '@/app/types/oddsHistory';

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const url = searchParams.get('url');
  const horseName = searchParams.get('horseName');

  if (!url || !horseName) {
    return NextResponse.json({ error: 'url と horseName パラメータが必要です' }, { status: 400 });
  }

  const rows = await getAlerts();

  // B列 === url かつ E列 === horseName でフィルタ
  const filtered = rows.filter((row) => row[1] === url && row[4] === horseName);

  // A列（検知日時）の昇順にソート
  filtered.sort((a, b) => (a[0] ?? '').localeCompare(b[0] ?? ''));

  const items: AlertHistoryItem[] = filtered.map((row) => ({
    detectedAt: row[0] ?? '',
    alertType: row[5] ?? '',
    value: parseFloat(row[6] ?? '0') || 0,
  }));

  return NextResponse.json(items);
}
