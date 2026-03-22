'use client';

import { useState, useEffect, useCallback } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer,
} from 'recharts';
import { OddsHistoryItem, HorseOption, AlertHistoryItem } from '@/app/types/oddsHistory';
import { AlertType } from '@/app/types/oddsAlert';

const ALERT_COLORS: Record<AlertType, string> = {
  '支持率急増': '#f97316',
  '順位乖離': '#7c3aed',
  'トレンド逸脱': '#dc2626',
};


type AlertMarker = { x: string; type: AlertType; value: number };

/** Sheets日時文字列 ("yyyy/MM/dd HH:mm:ss") を Date に変換する */
function parseDataAt(s: string): Date {
  const [datePart, timePart] = s.split(' ');
  const [y, m, d] = datePart.split('/').map(Number);
  const [h, min, sec] = timePart.split(':').map(Number);
  return new Date(y, m - 1, d, h, min, sec);
}

export default function OddsTrendChart() {
  const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080';

  const [urls, setUrls] = useState<string[]>([]);
  const [selectedUrl, setSelectedUrl] = useState('');

  const [horses, setHorses] = useState<HorseOption[]>([]);
  const [selectedHorse, setSelectedHorse] = useState('');

  const [chartData, setChartData] = useState<OddsHistoryItem[] | null>(null);
  const [alertMarkers, setAlertMarkers] = useState<AlertMarker[]>([]);
  const [isLoadingUrls, setIsLoadingUrls] = useState(true);
  const [isLoadingHorses, setIsLoadingHorses] = useState(false);
  const [isLoadingChart, setIsLoadingChart] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  /** マウント時にURL一覧を取得 */
  useEffect(() => {
    const fetchUrls = async () => {
      try {
        const res = await fetch(`${apiBaseUrl}/api/odds/history/urls`);
        if (!res.ok) throw new Error(`URLリスト取得失敗: ${res.status}`);
        const data: string[] = await res.json();
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
  }, [apiBaseUrl]);

  /** URL選択時に馬一覧をカスケード取得 */
  const handleUrlChange = useCallback(async (url: string) => {
    setSelectedUrl(url);
    setSelectedHorse('');
    setHorses([]);
    setChartData(null);
    setAlertMarkers([]);
    setErrorMessage(null);

    if (!url) return;

    setIsLoadingHorses(true);
    try {
      const res = await fetch(`${apiBaseUrl}/api/odds/history/horses?url=${encodeURIComponent(url)}`);
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
  }, [apiBaseUrl]);

  /** グラフ表示ボタン押下時にオッズ時系列とアラートを取得 */
  const handleShowChart = async () => {
    if (!selectedUrl || !selectedHorse) return;

    setIsLoadingChart(true);
    setChartData(null);
    setAlertMarkers([]);
    setErrorMessage(null);

    try {
      const params = new URLSearchParams({ url: selectedUrl, horseName: selectedHorse });
      const [oddsRes, alertsRes] = await Promise.all([
        fetch(`${apiBaseUrl}/api/odds/history?${params}`),
        fetch(`${apiBaseUrl}/api/odds/history/alerts?${params}`),
      ]);
      if (!oddsRes.ok) throw new Error(`オッズデータ取得失敗: ${oddsRes.status}`);

      const data: OddsHistoryItem[] = await oddsRes.json();
      if (data.length === 0) {
        setErrorMessage('該当データがありません。シートのデータが削除されている可能性があります');
        return;
      }

      // バックエンドがdetectedAt昇順でソート済みのため、そのままセット
      setChartData(data);

      // Alertsシートから取得済み（URL+馬名でサーバー側フィルタ済み）
      if (alertsRes.ok) {
        const alerts: AlertHistoryItem[] = await alertsRes.json();

        // アラート時刻に最も近いchartDataのdetectedAtを探してマーカー位置とする
        const markers: AlertMarker[] = alerts.map((a) => {
          const alertMs = parseDataAt(a.detectedAt).getTime();
          let nearest = data[0].detectedAt;
          let minDiff = Infinity;
          for (const item of data) {
            const diff = Math.abs(parseDataAt(item.detectedAt).getTime() - alertMs);
            if (diff < minDiff) {
              minDiff = diff;
              nearest = item.detectedAt;
            }
          }
          return { x: nearest, type: a.alertType as AlertType, value: a.value };
        });
        setAlertMarkers(markers);
      }
    } catch (e) {
      console.warn('オッズ時系列の取得に失敗しました', e);
      setErrorMessage('データの取得に失敗しました');
    } finally {
      setIsLoadingChart(false);
    }
  };

  /** データが複数日にまたがるか */
  const isMultiDay = chartData
    ? new Set(chartData.map((item) => item.detectedAt.split(' ')[0])).size > 1
    : false;

  /** X軸ラベル: "yyyy/MM/dd HH:mm:ss" → 単日: "HH:mm" / 複数日: "M/d HH:mm" */
  const formatTime = (value: string) => {
    const parts = value.split(' ');
    if (parts.length < 2) return value;
    const time = parts[1].slice(0, 5); // "HH:mm"
    if (!isMultiDay) return time;
    const dateParts = parts[0].split('/');
    return `${parseInt(dateParts[1])}/${parseInt(dateParts[2])} ${time}`;
  };

  const canShowChart = selectedUrl && selectedHorse && !isLoadingHorses;

  return (
    <div>
      <h2 className="text-base font-semibold text-gray-800 mb-4">オッズ推移グラフ</h2>

      {/* レースURL選択 */}
      <div className="mb-3">
        <label className="block text-xs font-medium text-gray-600 mb-1">レースURL</label>
        {isLoadingUrls ? (
          <p className="text-xs text-gray-400">読み込み中...</p>
        ) : (
          <select
            value={selectedUrl}
            onChange={(e) => handleUrlChange(e.target.value)}
            className="w-full px-3 py-2 text-sm border border-gray-300 text-gray-900 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
          >
            <option value="">-- URLを選択 --</option>
            {urls.map((url) => (
              <option key={url} value={url} title={url}>
                {url.length > 60 ? `...${url.slice(-55)}` : url}
              </option>
            ))}
          </select>
        )}
      </div>

      {/* 馬名選択 */}
      <div className="mb-4">
        <label className="block text-xs font-medium text-gray-600 mb-1">馬名</label>
        {isLoadingHorses ? (
          <p className="text-xs text-gray-400">読み込み中...</p>
        ) : (
          <select
            value={selectedHorse}
            onChange={(e) => {
              setSelectedHorse(e.target.value);
              setChartData(null);
              setAlertMarkers([]);
              setErrorMessage(null);
            }}
            disabled={!selectedUrl || horses.length === 0}
            className="w-full px-3 py-2 text-sm border border-gray-300 text-gray-900 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none disabled:bg-gray-100 disabled:text-gray-400"
          >
            <option value="">-- 馬を選択 --</option>
            {horses.map((h) => (
              <option key={h.horseNumber} value={h.horseName}>
                {h.horseNumber}番 {h.horseName}
              </option>
            ))}
          </select>
        )}
      </div>

      {/* グラフ表示ボタン */}
      <button
        onClick={handleShowChart}
        disabled={!canShowChart || isLoadingChart}
        className={`w-full py-2 px-4 rounded-md text-white text-sm font-medium transition-colors mb-4
          ${canShowChart && !isLoadingChart
            ? 'bg-blue-600 hover:bg-blue-700 active:bg-blue-800'
            : 'bg-gray-400 cursor-not-allowed'
          }`}
      >
        {isLoadingChart ? '取得中...' : 'グラフを表示'}
      </button>

      {/* エラー・空データメッセージ */}
      {errorMessage && (
        <p className="text-sm text-gray-500 text-center py-4">{errorMessage}</p>
      )}

      {/* グラフ本体 */}
      {chartData && chartData.length > 0 && (
        <div className="mt-2">
          <p className="text-xs text-gray-500 mb-2 text-center">
            {selectedHorse}（{chartData.length}件）
          </p>
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={chartData} margin={{ top: 16, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
              <XAxis
                dataKey="detectedAt"
                tickFormatter={formatTime}
                tick={{ fontSize: 11, fill: '#6b7280' }}
                angle={isMultiDay ? -35 : 0}
                textAnchor={isMultiDay ? 'end' : 'middle'}
                height={isMultiDay ? 48 : 30}
              />
              <YAxis
                tick={{ fontSize: 11, fill: '#6b7280' }}
                width={40}
              />
              <Tooltip
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                formatter={(value: any) =>
                  typeof value === 'number' ? value.toFixed(1) : '-'
                }
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                labelFormatter={(label: any) => `取得日時: ${label}`}
                contentStyle={{ fontSize: 12 }}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Line
                type="monotone"
                dataKey="winOdds"
                name="単勝"
                stroke="#2563eb"
                strokeWidth={2}
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                dot={(props: any) => {
                  const { cx, cy, payload } = props;
                  const marker = alertMarkers.find(m => m.x === payload.detectedAt);
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
              <Line
                type="monotone"
                dataKey="placeOddsMin"
                name="複勝下限"
                stroke="#16a34a"
                strokeWidth={2}
                dot={false}
                connectNulls
              />
              <Line
                type="monotone"
                dataKey="placeOddsMax"
                name="複勝上限"
                stroke="#16a34a"
                strokeWidth={1.5}
                strokeDasharray="5 5"
                dot={false}
                connectNulls
              />
            </LineChart>
          </ResponsiveContainer>

          {/* アラート凡例 */}
          {alertMarkers.length > 0 && (
            <div className="flex flex-wrap gap-3 justify-center mt-2">
              {(Object.keys(ALERT_COLORS) as AlertType[])
                .filter((type) => alertMarkers.some((m) => m.type === type))
                .map((type) => (
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
      )}
    </div>
  );
}
