package com.oddsalchemist.backend.dto;

/**
 * オッズ時系列データの1レコードを保持するRecordクラス。
 * フロントエンドのグラフ表示用に使用する。
 *
 * @param detectedAt   取得日時（形式: "yyyy/MM/dd HH:mm:ss"）
 * @param winOdds      単勝オッズ
 * @param placeOddsMin 複勝オッズ（下限）
 * @param placeOddsMax 複勝オッズ（上限）
 */
public record OddsHistoryItemDto(
        String detectedAt,
        Double winOdds,
        Double placeOddsMin,
        Double placeOddsMax
) {}
