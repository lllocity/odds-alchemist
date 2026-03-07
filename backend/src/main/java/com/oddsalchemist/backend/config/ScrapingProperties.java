package com.oddsalchemist.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml の odds.scraping 設定をバインドするプロパティクラス。
 * スケジュール間隔は動的に算出するため、cron設定は不要。
 */
@ConfigurationProperties(prefix = "odds.scraping")
public record ScrapingProperties(
        String sheetRange,
        /**
         * デバッグ用固定間隔（分）。0 の場合は発走時刻ベースの動的間隔を使用。
         */
        int debugIntervalMinutes
) {}
