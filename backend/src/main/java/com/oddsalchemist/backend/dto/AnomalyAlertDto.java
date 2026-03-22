package com.oddsalchemist.backend.dto;

/**
 * 異常検知アラートの情報を保持するRecordクラス。
 * フロントエンドへの通知や内部管理に使用する。
 *
 * @param raceName    レース名（例: "第91回 日本ダービー"）
 * @param horseNumber 馬番
 * @param horseName   馬名
 * @param alertType   検知タイプ（"支持率急増"、"順位乖離"、"トレンド逸脱"）
 * @param value       該当数値（支持率急増の場合は増加量、順位乖離の場合はギャップ値、トレンド逸脱の場合は逸脱量）
 * @param detectedAt  検知時刻（形式: "yyyy/MM/dd HH:mm:ss"）
 */
public record AnomalyAlertDto(
        String raceName,
        String horseNumber,
        String horseName,
        String alertType,
        double value,
        String detectedAt
) {}
