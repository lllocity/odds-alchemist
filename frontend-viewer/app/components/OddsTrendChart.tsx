'use client';

import { useState, useEffect, useCallback } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer,
} from 'recharts';
import { OddsHistoryItem, HorseOption, AlertHistoryItem, MergedChartItem } from '@/app/types/oddsHistory';
import { AlertType } from '@/app/types/oddsAlert';

const ALERT_COLORS: Record<AlertType, string> = {
  '支持率急増':           '#f97316', // orange
  '支持率加速':           '#ef4444', // red
  '順位乖離':             '#3b82f6', // blue
  '順位乖離[拡大中]':     '#0ea5e9', // sky
  '順位乖離[解消中]':     '#06b6d4', // cyan
  'トレンド逸脱':         '#7c3aed', // purple
  'フェーズ逸脱[朝]':     '#eab308', // yellow
  'フェーズ逸脱[30分前]': '#f59e0b', // amber
  'フェーズ逸脱[10分前]': '#f43f5e', // rose
  'オッズ断層[凝縮]':     '#22c55e', // green
  'オッズ断層[拡散]':     '#14b8a6', // teal
};

const MAX_HORSES = 3;
const HORSE_COLORS = ['#2563eb', '#e11d48', '#d97706'];

type AlertMarker = { x: string; type: AlertType; value: number };

/** Sheets日時文字列 ("yyyy/MM/dd HH:mm:ss") を Date に変換する */
function parseDataAt(s: string): Date {
  const [datePart, timePart] = s.split(' ');
  const [y, m, d] = datePart.split('/').map(Number);
  const [h, min, sec] = timePart.split(':').map(Number);
  return new Date(y, m - 1, d, h, min, sec);
}

/** 複数馬のOddsHistoryItemをタイムスタンプでマージする */
function mergeChartData(dataMap: Record<string, OddsHistoryItem[]>): MergedChartItem[] {
  const allTs = [...new Set(
    Object.values(dataMap).flatMap(items => items.map(i => i.detectedAt))
  )].sort();
  return allTs.map(ts => {
    const item: MergedChartItem = { detectedAt: ts };
    for (const [name, data] of Object.entries(dataMap)) {
      const found = data.find(d => d.detectedAt === ts);
      item[`${name}_win`]      = found?.winOdds      ?? null;
      item[`${name}_placeMin`] = found?.placeOddsMin ?? null;
      item[`${name}_placeMax`] = found?.placeOddsMax ?? null;
    }
    return item;
  });
}

export default function OddsTrendChart({ onUrlChange, disabled }: { onUrlChange?: (url: string) => void; disabled?: boolean }) {
  const [urls, setUrls] = useState<{ url: string; raceName: string }[]>([]);
  const [selectedUrl, setSelectedUrl] = useState('');

  const [horses, setHorses] = useState<HorseOption[]>([]);
  const [selectedHorses, setSelectedHorses] = useState<string[]>([]);

  const [mergedChartData, setMergedChartData] = useState<MergedChartItem[] | null>(null);
  const [alertMarkersMap, setAlertMarkersMap] = useState<Record<string, AlertMarker[]>>({});
  const [isLoadingUrls, setIsLoadingUrls] = useState(true);
  const [isLoadingHorses, setIsLoadingHorses] = useState(false);
  const [isLoadingChart, setIsLoadingChart] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  /** マウント時にURL一覧を取得 */
  useEffect(() => {
    const fetchUrls = async () => {
      try {
        const res = await fetch('/api/odds/history/urls');
        if (!res.ok) throw new Error(`URLリスト取得失敗: ${res.status}`);
        const data: { url: string; raceName: string }[] = await res.json();
        setUrls(data);
        if (data.length === 0) {
          setErrorMessage('OddsDataにデータがありません');
        }
      } catch (e) {
        console.warn('URL一覧の取得に失敗しました', e);
        setErrorMessage('URL一覧の取得に失敗しました');
      } finally {
        setIsLoadingUrls(false);
      }
    };
    fetchUrls();
  }, []);

  /** URL選択時に馬一覧をカスケード取得 */
  const handleUrlChange = useCallback(async (url: string) => {
    setSelectedUrl(url);
    onUrlChange?.(url);
    setSelectedHorses([]);
    setHorses([]);
    setErrorMessage(null);

    if (!url) return;

    setIsLoadingHorses(true);
    try {
      const res = await fetch(`/api/odds/history/horses?url=${encodeURIComponent(url)}`);
      if (!res.ok) throw new Error(`馬リスト取得失敗: ${res.status}`);
      const data: HorseOption[] = await res.json();
      setHorses(data);
      if (data.length === 0) {
        setErrorMessage('このレースにデータが見つかりません');
      }
    } catch (e) {
      console.warn('馬一覧の取得に失敗しました', e);
      setErrorMessage('馬一覧の取得に失敗しました');
    } finally {
      setIsLoadingHorses(false);
    }
  }, []);

  /** チェックボックス変更時にオッズ時系列とアラートを自動取得 */
  useEffect(() => {
    if (!selectedUrl || selectedHorses.length === 0) {
      setMergedChartData(null);
      setAlertMarkersMap({});
      return;
    }

    let cancelled = false;

    const fetchAll = async () => {
      setIsLoadingChart(true);
      setErrorMessage(null);

      try {
        const results = await Promise.all(
          selectedHorses.map(horse =>
            Promise.all([
              fetch(`/api/odds/history?${new URLSearchParams({ url: selectedUrl, horseName: horse })}`),
              fetch(`/api/odds/history/alerts?${new URLSearchParams({ url: selectedUrl, horseName: horse })}`),
            ]) as Promise<[Response, Response]>
          )
        );

        if (cancelled) return;

        const dataMap: Record<string, OddsHistoryItem[]> = {};
        const newAlertMarkersMap: Record<string, AlertMarker[]> = {};

        for (let i = 0; i < selectedHorses.length; i++) {
          const horseName = selectedHorses[i];
          const [oddsRes, alertsRes] = results[i];

          if (!oddsRes.ok) throw new Error(`オッズデータ取得失敗: ${oddsRes.status}`);
          const data: OddsHistoryItem[] = await oddsRes.json();
          if (data.length === 0) continue;
          dataMap[horseName] = data;

          if (alertsRes.ok) {
            const alerts: AlertHistoryItem[] = await alertsRes.json();
            const markers: AlertMarker[] = alerts.map((a) => {
              const alertMs = parseDataAt(a.detectedAt).getTime();
              let nearest = data[0].detectedAt;
              let minDiff = Infinity;
              for (const item of data) {
                const diff = Math.abs(parseDataAt(item.detectedAt).getTime() - alertMs);
                if (diff < minDiff) { minDiff = diff; nearest = item.detectedAt; }
              }
              return { x: nearest, type: a.alertType as AlertType, value: a.value };
            });
            newAlertMarkersMap[horseName] = markers;
          }
        }

        if (cancelled) return;

        if (Object.keys(dataMap).length === 0) {
          setErrorMessage('該当データがありません。シートのデータが削除されている可能性があります');
          return;
        }

        setMergedChartData(mergeChartData(dataMap));
        setAlertMarkersMap(newAlertMarkersMap);
      } catch (e) {
        if (cancelled) return;
        console.warn('オッズ時系列の取得に失敗しました', e);
        setErrorMessage('データの取得に失敗しました');
      } finally {
        if (!cancelled) setIsLoadingChart(false);
      }
    };

    fetchAll();
    return () => { cancelled = true; };
  }, [selectedHorses, selectedUrl]);

  /** データが複数日にまたがるか */
  const isMultiDay = mergedChartData
    ? new Set(mergedChartData.map(item => String(item.detectedAt).split(' ')[0])).size > 1
    : false;

  /** X軸ラベル: "yyyy/MM/dd HH:mm:ss" → 単日: "HH:mm" / 複数日: "M/d HH:mm" */
  const formatTime = (value: string) => {
    const parts = value.split(' ');
    if (parts.length < 2) return value;
    const time = parts[1].slice(0, 5);
    if (!isMultiDay) return time;
    const dateParts = parts[0].split('/');
    return `${parseInt(dateParts[1])}/${parseInt(dateParts[2])} ${time}`;
  };

  const hasAlerts = Object.values(alertMarkersMap).flat().length > 0;

  return (
    <div>
      <h2 className="text-base font-semibold text-gray-800 mb-4">オッズ推移グラフ</h2>

      {/* レース選択 */}
      <div className="mb-3">
        <label className="block text-xs font-medium text-gray-600 mb-1">レース名</label>
        {isLoadingUrls ? (
          <p className="text-xs text-gray-400">読み込み中...</p>
        ) : (
          <select
            value={selectedUrl}
            onChange={(e) => handleUrlChange(e.target.value)}
            disabled={disabled}
            className="w-full px-3 py-2 text-sm border border-gray-300 text-gray-900 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <option value="">-- レースを選択 --</option>
            {urls.map(({ url, raceName }) => (
              <option key={url} value={url} title={url}>
                {raceName || `...${url.slice(-55)}`}
              </option>
            ))}
          </select>
        )}
      </div>

      {/* 馬名選択（チェックボックス） */}
      <div className="mb-4">
        <div className="flex items-center justify-between mb-1">
          <label className="block text-xs font-medium text-gray-600">馬名</label>
          {horses.length > 0 && (
            <span className="text-xs text-gray-400">{selectedHorses.length}/{MAX_HORSES} 頭選択中</span>
          )}
        </div>
        {isLoadingHorses ? (
          <p className="text-xs text-gray-400">読み込み中...</p>
        ) : horses.length > 0 ? (
          <div className="space-y-0.5 border border-gray-200 rounded-md px-2 py-1">
            {horses.map((h) => {
              const isChecked = selectedHorses.includes(h.horseName);
              const isDisabled = !isChecked && selectedHorses.length >= MAX_HORSES;
              return (
                <label
                  key={h.horseNumber}
                  className={`flex items-center gap-2 px-1 py-1 rounded cursor-pointer text-sm
                    ${isDisabled ? 'opacity-40 cursor-not-allowed' : 'hover:bg-gray-50'}
                    ${isChecked ? 'text-gray-900 font-medium' : 'text-gray-600'}`}
                >
                  <input
                    type="checkbox"
                    checked={isChecked}
                    disabled={isDisabled}
                    onChange={() =>
                      setSelectedHorses(prev =>
                        prev.includes(h.horseName)
                          ? prev.filter(n => n !== h.horseName)
                          : [...prev, h.horseName]
                      )
                    }
                    className="accent-blue-600"
                  />
                  {h.horseNumber}番 {h.horseName}
                </label>
              );
            })}
          </div>
        ) : null}
      </div>

      {/* エラー・空データメッセージ */}
      {errorMessage && (
        <p className="text-sm text-gray-500 text-center py-4">{errorMessage}</p>
      )}

      {/* ローディング */}
      {isLoadingChart && (
        <p className="text-sm text-gray-400 text-center py-4">取得中...</p>
      )}

      {/* グラフ本体 */}
      {mergedChartData && mergedChartData.length > 0 && !isLoadingChart && (
        <div className="mt-2 space-y-6">
          <p className="text-xs text-gray-500 text-center">
            {selectedHorses.join('・')}（{mergedChartData.length}件）
          </p>

          {/* 単勝グラフ */}
          <div>
            <p className="text-xs font-medium text-gray-600 mb-1 text-center">単勝オッズ</p>
            <ResponsiveContainer width="100%" aspect={2.5} minWidth={1}>
              <LineChart data={mergedChartData} margin={{ top: 8, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                <XAxis
                  dataKey="detectedAt"
                  tickFormatter={(v) => formatTime(String(v))}
                  tick={{ fontSize: 11, fill: '#6b7280' }}
                  angle={isMultiDay ? -35 : 0}
                  textAnchor={isMultiDay ? 'end' : 'middle'}
                  height={isMultiDay ? 48 : 30}
                />
                <YAxis tick={{ fontSize: 11, fill: '#6b7280' }} width={40} />
                <Tooltip
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  formatter={(value: any) => typeof value === 'number' ? value.toFixed(1) : '-'}
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  labelFormatter={(label: any) => `取得日時: ${label}`}
                  contentStyle={{ fontSize: 12 }}
                />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                {selectedHorses.map((name, i) => (
                  <Line
                    key={name}
                    type="monotone"
                    dataKey={`${name}_win`}
                    name={name}
                    stroke={HORSE_COLORS[i]}
                    strokeWidth={2}
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    dot={(props: any) => {
                      const { cx, cy, payload } = props;
                      const markers = alertMarkersMap[name] ?? [];
                      const marker = markers.find(m => m.x === payload.detectedAt);
                      if (!marker || cy == null) return <g key={props.key} />;
                      return (
                        <circle
                          key={props.key}
                          cx={cx}
                          cy={cy}
                          r={5}
                          fill={ALERT_COLORS[marker.type]}
                          stroke="white"
                          strokeWidth={1.5}
                        />
                      );
                    }}
                    connectNulls
                  />
                ))}
              </LineChart>
            </ResponsiveContainer>

            {/* アラート凡例 */}
            {hasAlerts && (
              <div className="flex flex-wrap gap-3 justify-center mt-1">
                {(Object.keys(ALERT_COLORS) as AlertType[])
                  .filter(type => Object.values(alertMarkersMap).flat().some(m => m.type === type))
                  .map(type => (
                    <span key={type} className="flex items-center gap-1.5 text-xs text-gray-600">
                      <span
                        className="inline-block w-3 h-3 rounded-full border border-white"
                        style={{ backgroundColor: ALERT_COLORS[type] }}
                      />
                      {type}
                    </span>
                  ))}
              </div>
            )}
          </div>

          {/* 複勝グラフ */}
          <div>
            <p className="text-xs font-medium text-gray-600 mb-1 text-center">複勝オッズ</p>
            <ResponsiveContainer width="100%" aspect={2.5} minWidth={1}>
              <LineChart data={mergedChartData} margin={{ top: 8, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                <XAxis
                  dataKey="detectedAt"
                  tickFormatter={(v) => formatTime(String(v))}
                  tick={{ fontSize: 11, fill: '#6b7280' }}
                  angle={isMultiDay ? -35 : 0}
                  textAnchor={isMultiDay ? 'end' : 'middle'}
                  height={isMultiDay ? 48 : 30}
                />
                <YAxis tick={{ fontSize: 11, fill: '#6b7280' }} width={40} />
                <Tooltip
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  formatter={(value: any) => typeof value === 'number' ? value.toFixed(1) : '-'}
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  labelFormatter={(label: any) => `取得日時: ${label}`}
                  contentStyle={{ fontSize: 12 }}
                />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                {selectedHorses.map((name, i) => [
                  <Line
                    key={`${name}_min`}
                    type="monotone"
                    dataKey={`${name}_placeMin`}
                    name={name}
                    stroke={HORSE_COLORS[i]}
                    strokeWidth={2}
                    dot={false}
                    connectNulls
                  />,
                  <Line
                    key={`${name}_max`}
                    type="monotone"
                    dataKey={`${name}_placeMax`}
                    name={`${name}_max`}
                    legendType="none"
                    stroke={HORSE_COLORS[i]}
                    strokeWidth={1.5}
                    strokeDasharray="5 5"
                    dot={false}
                    connectNulls
                  />,
                ])}
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}
    </div>
  );
}
