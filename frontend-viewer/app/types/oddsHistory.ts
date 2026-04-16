/** OddsDataシートの1レコード（オッズ時系列の1点）を表す型 */
export type OddsHistoryItem = {
  detectedAt: string;
  winOdds: number | null;
  placeOddsMin: number | null;
  placeOddsMax: number | null;
};

/** 馬番・馬名の組を表す型（ドロップダウン選択肢用） */
export type HorseOption = {
  horseNumber: number;
  horseName: string;
};

/** Alertsシートのアラートをグラフプロット用に表す型 */
export type AlertHistoryItem = {
  detectedAt: string;
  alertType: string;
  value: number;
};
