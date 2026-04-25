export type AlertType =
  | '支持率急増'
  | '順位乖離'
  | '順位乖離[拡大中]'
  | '順位乖離[解消中]'
  | 'トレンド逸脱'
  | '支持率加速'
  | 'フェーズ逸脱[朝]'
  | 'フェーズ逸脱[30分前]'
  | 'フェーズ逸脱[10分前]'
  | 'オッズ断層[凝縮]'
  | 'オッズ断層[拡散]';

export interface AnomalyAlert {
  /** レース名 */
  raceName: string;
  /** 馬番 */
  horseNumber: string;
  /** 馬名 */
  horseName: string;
  /** 検知タイプ */
  alertType: AlertType;
  /** 該当数値 */
  value: number;
  /** 検知時刻（形式: "yyyy/MM/dd HH:mm:ss"） */
  detectedAt: string;
}
