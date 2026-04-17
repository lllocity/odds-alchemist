package com.oddsalchemist.backend.dto;

/**
 * 監視対象URL一覧のレスポンス用 DTO。
 * URLと実行時刻に加え、OddsData シートから取得したレース名を付加する。
 */
public record TargetUrlResponseDto(
        String url,
        String raceName,
        String lastExecutionTime,
        String nextScheduledTime
) {}
