import { AnomalyAlert, AlertType } from '@/app/types/oddsAlert';

/** 検知タイプごとのスタイル・説明・意図・値フォーマット設定 */
const ALERT_TYPE_CONFIG: Record<AlertType, {
  bg: string;
  text: string;
  label: string;
  description: string;
  intent: string;
  formatValue: (v: number) => string;
}> = {
  支持率急増: {
    bg: 'bg-orange-100',
    text: 'text-orange-800',
    label: '支持率急増',
    description: '支持率（1/オッズ）の前回比増加が +2.0% 以上（4番人気以下）',
    intent: '短時間で急速に資金が集まっている。玄人筋・関係者の仕込みの可能性あり。三連複・ワイドの買い目候補に組み込む。',
    formatValue: (v) => `+${(v * 100).toFixed(1)}%`,
  },
  支持率加速: {
    bg: 'bg-red-100',
    text: 'text-red-800',
    label: '支持率加速',
    description: '支持率の変化速度が 0.5%/分 以上（4番人気以下）',
    intent: '時間あたりの変化が急加速しており、単発の急増より「流れ」がある。スクレイピング間隔に依存しない定量評価。支持率急増と組み合わさると最強の仕込みシグナル。',
    formatValue: (v) => `${(v * 100).toFixed(2)}%/分`,
  },
  順位乖離: {
    bg: 'bg-blue-100',
    text: 'text-blue-800',
    label: '順位乖離',
    description: '単勝人気順位と複勝人気順位の差が 3 以上',
    intent: '「勝ちきれないが馬券には来る」という市場評価。複勝・ワイドの軸馬として積極採用。三連複の相手候補として最優先。',
    formatValue: (v) => `${v}差`,
  },
  '順位乖離[拡大中]': {
    bg: 'bg-sky-100',
    text: 'text-sky-800',
    label: '乖離拡大中',
    description: '単複順位乖離が前回より拡大（複勝がさらに売れ続けている）',
    intent: '「複勝だけ売れる」状態が強まっている。複勝・ワイドを仕込み続けるタイミング。乖離が継続する限り「買い継続」の判断材料。',
    formatValue: (v) => `+${v}差`,
  },
  '順位乖離[解消中]': {
    bg: 'bg-cyan-100',
    text: 'text-cyan-800',
    label: '乖離解消中',
    description: '単複順位乖離が前回より縮小（単勝が追いついてきた）',
    intent: '「1着もありうる」と市場が評価し始めた。複勝オッズの旨味が低下するタイミング。単勝・馬単への切り替えを検討。',
    formatValue: (v) => `-${v}差`,
  },
  トレンド逸脱: {
    bg: 'bg-purple-100',
    text: 'text-purple-800',
    label: 'トレンド逸脱',
    description: '当日初回基準値からの支持率増加が +5.0% 以上（5〜12番人気の中穴・大穴帯）',
    intent: '朝から見て大きく評価が変わった中穴・大穴。当日の注目馬として三連複・ワイドの相手候補に組み込む価値あり。',
    formatValue: (v) => `+${(v * 100).toFixed(1)}%`,
  },
  'フェーズ逸脱[朝]': {
    bg: 'bg-yellow-100',
    text: 'text-yellow-800',
    label: 'フェーズ逸脱[朝]',
    description: '朝の最初のオッズからの支持率増加が +5.0% 以上（4番人気以下）',
    intent: '朝から継続的に評価が上昇している馬。長時間にわたる資金流入で信頼度が高い。三連複の相手として安定して組み込める。',
    formatValue: (v) => `+${(v * 100).toFixed(1)}%`,
  },
  'フェーズ逸脱[30分前]': {
    bg: 'bg-amber-100',
    text: 'text-amber-800',
    label: 'フェーズ逸脱[30分前]',
    description: '発走30分前時点の基準値からの支持率増加が +5.0% 以上（4番人気以下）',
    intent: '直前30分フェーズで新たに動き始めた馬。情報筋の直前仕込みの可能性。フェーズ逸脱[朝]との同時検知で確信度が上がる。',
    formatValue: (v) => `+${(v * 100).toFixed(1)}%`,
  },
  'フェーズ逸脱[10分前]': {
    bg: 'bg-rose-100',
    text: 'text-rose-800',
    label: 'フェーズ逸脱[10分前]',
    description: '発走10分前時点の基準値からの支持率増加が +5.0% 以上（4番人気以下）',
    intent: '最終局面の急激な変化。厩舎・調教師コメント等の直前情報が反映された可能性が高い最重要シグナル。即座に買い目を確定する。',
    formatValue: (v) => `+${(v * 100).toFixed(1)}%`,
  },
  'オッズ断層[凝縮]': {
    bg: 'bg-green-100',
    text: 'text-green-800',
    label: '断層凝縮',
    description: '断層位置が上位方向に移動（特定馬への絞り込みが進行）',
    intent: '市場が「この数頭の争い」へ収束しつつある。断層内の上位馬を本命視して三連単の的中率アップ。三連複・馬連の軸も絞り込める。',
    formatValue: (v) => `比率 ${v.toFixed(2)}倍`,
  },
  'オッズ断層[拡散]': {
    bg: 'bg-teal-100',
    text: 'text-teal-800',
    label: '断層拡散',
    description: '断層位置が下位方向に移動（混戦化・下位馬への資金流入が進行）',
    intent: '本命馬のオッズ妙味が低下し、下位馬にも資金が流入中。穴狙い戦略への切り替えを検討。三連複のヒモを広めに取る。',
    formatValue: (v) => `比率 ${v.toFixed(2)}倍`,
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
        <dl className="space-y-2.5">
          {(Object.entries(ALERT_TYPE_CONFIG) as [AlertType, typeof ALERT_TYPE_CONFIG[AlertType]][]).map(
            ([type, config]) => (
              <div key={type} className="flex items-start gap-2 text-xs">
                <span className={`shrink-0 font-medium px-1.5 py-0.5 rounded ${config.bg} ${config.text}`}>
                  {config.label}
                </span>
                <div className="min-w-0">
                  <p className="text-gray-400">{config.description}</p>
                  <p className="text-gray-600 mt-0.5">{config.intent}</p>
                </div>
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
                <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 mb-1">
                  <span
                    className={`shrink-0 text-xs font-medium px-2 py-0.5 rounded ${config.bg} ${config.text}`}
                  >
                    {config.label}
                  </span>
                  <span className="text-xs text-gray-400 truncate min-w-0 flex-1">{alert.raceName}</span>
                  <span className="shrink-0 text-xs text-gray-400">
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
