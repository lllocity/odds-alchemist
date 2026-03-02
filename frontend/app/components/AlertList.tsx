import { AnomalyAlert, AlertType } from '@/app/types/oddsAlert';

/** 検知タイプごとのラベル色設定 */
const ALERT_TYPE_STYLES: Record<AlertType, { bg: string; text: string; label: string }> = {
  支持率急増: { bg: 'bg-orange-100', text: 'text-orange-800', label: '支持率急増' },
  順位乖離:   { bg: 'bg-blue-100',   text: 'text-blue-800',   label: '順位乖離' },
  トレンド逸脱: { bg: 'bg-purple-100', text: 'text-purple-800', label: 'トレンド逸脱' },
};

/** ISO-8601文字列を "HH:mm:ss" 形式に整形する */
function formatTime(isoString: string): string {
  try {
    const date = new Date(isoString);
    return date.toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch {
    return isoString;
  }
}

interface AlertListProps {
  alerts: AnomalyAlert[];
  lastUpdated: Date | null;
}

/**
 * 異常検知アラートを一覧表示するコンポーネント。
 * 検知タイプによってラベル色を分け、直感的に状況を把握できるようにする。
 */
export default function AlertList({ alerts, lastUpdated }: AlertListProps) {
  return (
    <div className="w-full">
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-lg font-semibold text-gray-800">検知アラート</h2>
        {lastUpdated && (
          <span className="text-xs text-gray-400">
            最終更新: {lastUpdated.toLocaleTimeString('ja-JP')}
          </span>
        )}
      </div>

      {alerts.length === 0 ? (
        <div className="text-sm text-gray-500 py-6 text-center border border-dashed border-gray-200 rounded-md">
          現在、検知されたアラートはありません
        </div>
      ) : (
        <ul className="space-y-2">
          {alerts.map((alert, index) => {
            const style = ALERT_TYPE_STYLES[alert.alertType] ?? {
              bg: 'bg-gray-100',
              text: 'text-gray-800',
              label: alert.alertType,
            };

            return (
              <li
                key={index}
                className="flex items-center gap-3 p-3 bg-white border border-gray-100 rounded-md"
              >
                {/* 検知タイプラベル */}
                <span
                  className={`shrink-0 text-xs font-medium px-2 py-0.5 rounded ${style.bg} ${style.text}`}
                >
                  {style.label}
                </span>

                {/* 馬番・馬名 */}
                <span className="text-sm text-gray-700 font-medium min-w-0 truncate">
                  {alert.horseNumber}番 {alert.horseName}
                </span>

                {/* 数値 */}
                <span className="shrink-0 text-sm text-gray-500 ml-auto">
                  {(alert.value * 100).toFixed(1)}%
                </span>

                {/* 検知時刻 */}
                <span className="shrink-0 text-xs text-gray-400">
                  {formatTime(alert.detectedAt)}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
