import { AnomalyAlert, AlertType } from '@/app/types/oddsAlert';

/** 検知タイプごとのスタイル・説明・値フォーマット設定 */
const ALERT_TYPE_CONFIG: Record<AlertType, {
  bg: string;
  text: string;
  label: string;
  description: string;
  formatValue: (v: number) => string;
}> = {
  支持率急増: {
    bg: 'bg-orange-100',
    text: 'text-orange-800',
    label: '支持率急増',
    description: '支持率（1/オッズ）の前回比増加が +2.0% 以上（4番人気以下）',
    formatValue: (v) => `+${(v * 100).toFixed(1)}%`,
  },
  順位乖離: {
    bg: 'bg-blue-100',
    text: 'text-blue-800',
    label: '順位乖離',
    description: '単勝人気順位と複勝人気順位の差が 3 以上（単勝より複勝が相対的に有利）',
    formatValue: (v) => `${v}差`,
  },
  トレンド逸脱: {
    bg: 'bg-purple-100',
    text: 'text-purple-800',
    label: 'トレンド逸脱',
    description: '当日初回基準値からの支持率増加が +5.0% 以上（5〜12番人気の中穴・大穴帯）',
    formatValue: (v) => `+${(v * 100).toFixed(1)}%`,
  },
};

/** "yyyy/MM/dd HH:mm:ss" 形式の文字列から時刻部分 "HH:mm:ss" を返す */
function formatTime(detectedAt: string): string {
  const timePart = detectedAt.split(' ')[1];
  return timePart ?? detectedAt;
}

interface AlertListProps {
  alerts: AnomalyAlert[];
  lastUpdated: Date | null;
}

/**
 * 異常検知アラートを一覧表示するコンポーネント。
 * 検知タイプの凡例と、レース名・馬番・馬名・数値・検知時刻を表示する。
 */
export default function AlertList({ alerts, lastUpdated }: AlertListProps) {
  return (
    <div className="w-full">
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-lg font-semibold text-gray-800">検知アラート<span className="text-xs font-normal text-gray-400 ml-2">最新30件</span></h2>
        {lastUpdated && (
          <span className="text-xs text-gray-400">
            最終更新: {lastUpdated.toLocaleTimeString('ja-JP')}
          </span>
        )}
      </div>

      {/* 検知種別の凡例 */}
      <div className="mb-4 p-3 bg-gray-50 rounded-md border border-gray-200">
        <p className="text-xs font-semibold text-gray-600 mb-2">検知種別の説明</p>
        <dl className="space-y-1.5">
          {(Object.entries(ALERT_TYPE_CONFIG) as [AlertType, typeof ALERT_TYPE_CONFIG[AlertType]][]).map(
            ([type, config]) => (
              <div key={type} className="flex items-start gap-2 text-xs">
                <span className={`shrink-0 font-medium px-1.5 py-0.5 rounded ${config.bg} ${config.text}`}>
                  {config.label}
                </span>
                <span className="text-gray-500">{config.description}</span>
              </div>
            )
          )}
        </dl>
      </div>

      {alerts.length === 0 ? (
        <div className="text-sm text-gray-500 py-6 text-center border border-dashed border-gray-200 rounded-md">
          現在、検知されたアラートはありません
        </div>
      ) : (
        <ul className="space-y-2">
          {alerts.map((alert, index) => {
            const config = ALERT_TYPE_CONFIG[alert.alertType] ?? {
              bg: 'bg-gray-100',
              text: 'text-gray-800',
              label: alert.alertType,
              description: '',
              formatValue: (v: number) => String(v),
            };

            return (
              <li
                key={index}
                className="p-3 bg-white border border-gray-100 rounded-md"
              >
                {/* 1行目: タイプラベル・レース名・検知時刻 */}
                <div className="flex items-center gap-2 mb-1">
                  <span
                    className={`shrink-0 text-xs font-medium px-2 py-0.5 rounded ${config.bg} ${config.text}`}
                  >
                    {config.label}
                  </span>
                  <span className="text-xs text-gray-400 truncate">{alert.raceName}</span>
                  <span className="shrink-0 text-xs text-gray-400 ml-auto">
                    {formatTime(alert.detectedAt)}
                  </span>
                </div>
                {/* 2行目: 馬番・馬名・数値 */}
                <div className="flex items-center gap-2">
                  <span className="text-sm text-gray-700 font-medium">
                    {alert.horseNumber}番 {alert.horseName}
                  </span>
                  <span className="shrink-0 text-sm text-gray-500 ml-auto">
                    {config.formatValue(alert.value)}
                  </span>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
