package com.oddsalchemist.backend.dto;

/**
 * 異常検知アラートの情報を保持するRecordクラス。
 * フロントエンドへの通知や内部管理に使用する。
 *
 * @param horseNumber 馬番
 * @param horseName   馬名
 * @param alertType   検知タイプ（"支持率急増" または "順位乖離"）
 * @param value       該当数値（支持率急増の場合は増加量、順位乖離の場合はギャップ値）
 */
public record AnomalyAlertDto(
        String horseNumber,
        String horseName,
        String alertType,
        double value
) {}
