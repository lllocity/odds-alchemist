package com.oddsalchemist.backend.dto;

/**
 * 抽出したオッズ情報を保持するRecordクラス
 */
public record OddsData(
    String raceName,
    String horseNumber,
    String horseName,
    Double winOdds,
    Double placeOddsMin,
    Double placeOddsMax
) {}