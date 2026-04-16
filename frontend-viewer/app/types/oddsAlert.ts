export type AlertType = '支持率急増' | '順位乖離' | 'トレンド逸脱';

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
