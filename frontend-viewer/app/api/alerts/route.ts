import { NextResponse } from 'next/server';
import { getAlerts } from '@/lib/sheets';
import { AnomalyAlert } from '@/app/types/oddsAlert';

export async function GET() {
  const rows = await getAlerts();

  const alerts: AnomalyAlert[] = rows.map((row) => ({
    detectedAt: row[0] ?? '',
    raceName: row[2] ?? '',
    horseNumber: row[3] ?? '',
    horseName: row[4] ?? '',
    alertType: (row[5] ?? '') as AnomalyAlert['alertType'],
    value: parseFloat(row[6] ?? '0') || 0,
  }));

  const latest = [...alerts].reverse().slice(0, 30);

  return NextResponse.json(latest);
}
