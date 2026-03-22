package com.oddsalchemist.backend.dto;

/**
 * Alertsシートの1レコードを保持するRecordクラス。
 * フロントエンドのグラフへのアラートプロット用に使用する。
 *
 * @param detectedAt 検知日時（Sheets形式: "yyyy/MM/dd HH:mm:ss"）
 * @param alertType  検知タイプ（"支持率急増"、"順位乖離"、"トレンド逸脱"）
 * @param value      該当数値
 */
public record AlertHistoryItemDto(
        String detectedAt,
        String alertType,
        double value
) {}
