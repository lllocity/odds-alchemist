package com.oddsalchemist.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * application.yaml の odds.scraping 設定をバインドするプロパティクラス。
 * スケジュール間隔は動的に算出するため、cron設定は不要。
 */
@ConfigurationProperties(prefix = "odds.scraping")
public record ScrapingProperties(
        String sheetRange,
        List<String> targetUrls
) {}
