/**
 * バックエンドの AnomalyAlertDto に対応する型定義。
 * フィールド名・型はバックエンドのJavaレコードと必ず一致させること。
 *
 * alertType の値は OddsAnomalyDetector が生成する文字列リテラルに合わせる。
 * detectedAt は Spring Boot の Jackson 設定により ISO-8601 文字列で渡される。
 */
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
  /** 該当数値（支持率急増: 増加量、順位乖離: ギャップ値、トレンド逸脱: 逸脱量） */
  value: number;
  /** 検知時刻（形式: "yyyy/MM/dd HH:mm:ss"） */
  detectedAt: string;
}
